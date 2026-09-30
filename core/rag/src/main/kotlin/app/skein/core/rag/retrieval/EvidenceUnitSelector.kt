package app.skein.core.rag.retrieval

import app.skein.core.model.Locator
import app.skein.core.model.PersonaId
import app.skein.core.model.Retrieved
import app.skein.core.model.RevisionHashing
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chunk.BlockKind
import app.skein.core.rag.chunk.MarkdownBlockScanner

/**
 * Opt-in structural selection over current, byte-verified NOTE revisions. Blank lines delimit
 * units except inside fenced code; contiguous tables/lists stay together. This is not a Markdown
 * AST, semantic completeness, contradiction detection or fact entailment. Oversized or unclosed
 * units are refused, never shortened. Prompt budgeting can then remove entire returned units.
 */
public class EvidenceUnitSelector(
    private val repository: VaultRepository,
    private val legacyPersonaId: PersonaId? = null,
    private val maxUnitBytes: Int = 8_192,
) {
    init {
        require(maxUnitBytes > 0)
    }

    public suspend fun select(
        candidates: List<Retrieved>,
        personaId: PersonaId?,
        expandUnits: Boolean = true,
    ): EvidenceSelection {
        val units = mutableListOf<EvidenceUnit>()
        val exclusions = linkedMapOf<EvidenceExclusion, Int>()

        fun exclude(reason: EvidenceExclusion) {
            exclusions[reason] = (exclusions[reason] ?: 0) + 1
        }
        for (candidate in candidates) {
            val expanded = expand(candidate, personaId, expandUnits)
            val source = expanded.first
            if (source == null) {
                exclude(checkNotNull(expanded.second))
                continue
            }
            if (!expandUnits) {
                units += EvidenceUnit(source, listOf(candidate))
                continue
            }
            val overlaps = overlappingIndices(units, source)
            if (overlaps.isEmpty()) {
                units += EvidenceUnit(source, listOf(candidate))
                continue
            }
            val pieces = overlaps.map { units[it].source } + source
            val start = pieces.minOf { checkNotNull(it.locator).byteStart }
            val end = pieces.maxOf { checkNotNull(it.locator).byteEnd }
            if (end - start > maxUnitBytes) {
                exclude(EvidenceExclusion.OVERLAP_TOO_LARGE)
                continue
            }
            // Every piece was independently verified against this same current revision. The
            // transitive overlap set has no gaps; copying their exact bytes invents no join text.
            val bytes = ByteArray(end - start)
            for (piece in pieces) {
                piece.text.toByteArray(Charsets.UTF_8).copyInto(bytes, checkNotNull(piece.locator).byteStart - start)
            }
            val first = overlaps.first()
            val members = (overlaps.flatMap { units[it].members } + candidate).sortedBy { candidates.indexOf(it) }
            units[first] =
                EvidenceUnit(
                    units[first].source.copy(text = bytes.toString(Charsets.UTF_8), locator = Locator(start, end)),
                    members,
                )
            for (index in overlaps.drop(1).asReversed()) units.removeAt(index)
            exclude(EvidenceExclusion.DUPLICATE)
        }
        // Re-read after expansion: observed deletes, revision edits or Space moves refuse publication.
        // This read API does not promise an atomic snapshot against a later writer.
        val current =
            units.filter { unit ->
                val document = repository.getDocument(unit.source.docId)
                val reason =
                    when {
                        document == null -> EvidenceExclusion.MISSING_SOURCE
                        !document.isAuthoritativeEvidence() -> EvidenceExclusion.UNSUPPORTED_SOURCE
                        !document.isEvidenceInScope(personaId, legacyPersonaId) -> EvidenceExclusion.OUT_OF_SCOPE
                        document.contentHash != unit.source.revisionHash -> EvidenceExclusion.STALE_REVISION
                        else -> null
                    }
                if (reason != null) exclude(reason)
                reason == null
            }
        return EvidenceSelection(current, exclusions)
    }

    /** Transitive interval union also handles a later chunk that bridges two prior units. */
    private fun overlappingIndices(
        units: List<EvidenceUnit>,
        source: Retrieved,
    ): List<Int> {
        val anchor = checkNotNull(source.locator)
        var start = anchor.byteStart
        var end = anchor.byteEnd
        val found = mutableSetOf<Int>()
        do {
            var changed = false
            for ((index, unit) in units.withIndex()) {
                val prior = unit.source
                if (index in found || prior.docId != source.docId || prior.revisionHash != source.revisionHash) continue
                val locator = checkNotNull(prior.locator)
                if (locator.byteStart < end && locator.byteEnd > start) {
                    found += index
                    start = minOf(start, locator.byteStart)
                    end = maxOf(end, locator.byteEnd)
                    changed = true
                }
            }
        } while (changed)
        return found.sorted()
    }

    private suspend fun expand(
        candidate: Retrieved,
        personaId: PersonaId?,
        expandUnits: Boolean,
    ): Pair<Retrieved?, EvidenceExclusion?> {
        val document = repository.getDocument(candidate.docId) ?: return refused(EvidenceExclusion.MISSING_SOURCE)
        if (!document.isAuthoritativeEvidence()) return refused(EvidenceExclusion.UNSUPPORTED_SOURCE)
        if (!document.isEvidenceInScope(personaId, legacyPersonaId)) return refused(EvidenceExclusion.OUT_OF_SCOPE)
        val hash = candidate.revisionHash ?: return refused(EvidenceExclusion.MISSING_REVISION)
        val revision = repository.currentRevision(candidate.docId) ?: return refused(EvidenceExclusion.MISSING_REVISION)
        if (revision.documentId != candidate.docId || document.contentHash != hash || revision.revisionHash != hash) {
            return refused(EvidenceExclusion.STALE_REVISION)
        }
        val body = revision.bodyMdSnapshot.toByteArray(Charsets.UTF_8)
        if (body.size > MAX_SNAPSHOT_BYTES) return refused(EvidenceExclusion.UNIT_TOO_LARGE)
        if (RevisionHashing.compute(revision.bodyMdSnapshot, revision.frontmatterSnapshot) != hash) {
            return refused(EvidenceExclusion.STALE_REVISION)
        }
        val anchor = candidate.locator ?: return refused(EvidenceExclusion.INVALID_ANCHOR)
        if (anchor.byteStart < 0 || anchor.byteEnd <= anchor.byteStart || anchor.byteEnd > body.size) {
            return refused(EvidenceExclusion.INVALID_ANCHOR)
        }
        if (!isUtf8Boundary(body, anchor.byteStart) || !isUtf8Boundary(body, anchor.byteEnd)) {
            return refused(EvidenceExclusion.INVALID_ANCHOR)
        }
        val original = RevisionHashing.canonicalBody(candidate.text).toByteArray(Charsets.UTF_8)
        val slice = body.copyOfRange(anchor.byteStart, anchor.byteEnd)
        if (!slice.contentEquals(original)) {
            val heading =
                headingBreadcrumbAtEnd(revision.bodyMdSnapshot, body, anchor.byteEnd)
                    ?: return refused(EvidenceExclusion.INVALID_ANCHOR)
            // Ingest stores embeddingText, but only an exactly source-derived breadcrumb is legal.
            // The metadata never enters the returned evidence: that is built from body below.
            val indexed = "$heading\n\n".toByteArray(Charsets.UTF_8) + slice
            if (!indexed.contentEquals(original)) return refused(EvidenceExclusion.INVALID_ANCHOR)
        }
        if (!expandUnits) {
            return candidate.copy(
                docTitle = document.title,
                text = slice.toString(Charsets.UTF_8),
                sourceKind = document.kind,
            ) to null
        }
        val blocks = blocks(body).filter { it.start < anchor.byteEnd && it.end > anchor.byteStart }
        if (blocks.isEmpty() || blocks.any { !it.complete }) return refused(EvidenceExclusion.INCOMPLETE_UNIT)
        // Preserve boundary whitespace from the verified original anchor as well as whole blocks.
        val start = minOf(blocks.first().start, anchor.byteStart)
        val end = maxOf(blocks.last().end, anchor.byteEnd)
        if (end - start > maxUnitBytes) return refused(EvidenceExclusion.UNIT_TOO_LARGE)
        return candidate.copy(
            docTitle = document.title,
            text = body.copyOfRange(start, end).toString(Charsets.UTF_8),
            sourceKind = document.kind,
            locator = Locator(start, end),
        ) to null
    }

    private fun isUtf8Boundary(
        body: ByteArray,
        offset: Int,
    ): Boolean = offset == body.size || body[offset].toInt() and 0xc0 != 0x80

    /**
     * Chunker.pack uses its LAST unit's heading context, including a chunk spanning headings.
     * Reuse its scanner (including Setext and fenced-heading handling), then the same heading
     * stack/format convention. No tokenizer or re-chunking is needed to verify this metadata.
     * Parity tests exercise actual Chunker -> ingest -> assembler output; convention drift refuses
     * provenance instead of accepting an arbitrary prefix that happens to end in source text.
     */
    private fun headingBreadcrumbAtEnd(
        bodyText: String,
        body: ByteArray,
        end: Int,
    ): String? {
        val charEnd = body.copyOfRange(0, end).toString(Charsets.UTF_8).length
        val stack = mutableListOf<Pair<Int, String>>()
        for (block in MarkdownBlockScanner.scan(bodyText)) {
            if (block.start >= charEnd) break
            if (block.kind == BlockKind.HEADING) {
                // Production chunks never end part-way through an indivisible heading block.
                if (block.end > charEnd) return null
                while (stack.isNotEmpty() && stack.last().first >= block.headingLevel) stack.removeAt(stack.lastIndex)
                stack += block.headingLevel to block.headingText
            }
        }
        return stack.takeIf { it.isNotEmpty() }?.joinToString(" › ") { (level, text) -> "#".repeat(level) + " " + text }
    }

    private fun refused(reason: EvidenceExclusion): Pair<Retrieved?, EvidenceExclusion?> = null to reason

    private data class Block(
        val start: Int,
        val end: Int,
        val complete: Boolean,
    )

    private fun blocks(body: ByteArray): List<Block> {
        val out = mutableListOf<Block>()
        var blockStart: Int? = null
        var blockEnd = 0
        var lineStart = 0
        var fence: String? = null
        while (lineStart < body.size) {
            var lineEnd = lineStart
            while (lineEnd < body.size && body[lineEnd] != '\n'.code.toByte()) lineEnd++
            val next = if (lineEnd < body.size) lineEnd + 1 else lineEnd
            val line = body.copyOfRange(lineStart, lineEnd).toString(Charsets.UTF_8)
            val marker = FENCE.find(line)?.groupValues?.get(1)
            val active = fence
            if (active != null) {
                if (marker != null &&
                    marker.first() == active.first() &&
                    marker.length >= active.length &&
                    line.trim().all { it == active.first() }
                ) {
                    fence = null
                }
            } else if (marker != null) {
                fence = marker
            }
            if (line.isBlank() && active == null && fence == null) {
                blockStart?.let { out += Block(it, blockEnd, true) }
                blockStart = null
            } else {
                if (blockStart == null) blockStart = lineStart
                blockEnd = next
            }
            lineStart = next
        }
        blockStart?.let { out += Block(it, blockEnd, fence == null) }
        return out
    }

    private companion object {
        const val MAX_SNAPSHOT_BYTES = 1_048_576
        val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")
    }
}

// `E10.I4`: deterministic synthetic vault fixture generator. Seeds a
// `VaultRepository` with a mix of notes, chats, and externally-sourced
// attachments so retrieval/RAG/ingest tests (`E5.I17`, `E5.I19`, `E10.I5`)
// have a realistic-ish, reproducible vault to run against without a real
// device or a hand-authored fixture directory.
//
// This fixture generates DOCUMENTS only — it does not chunk, embed, or
// touch `IndexStore`/`Edge` rows. `Chunk` generation is the ingest
// pipeline's job (`core/rag`, `E5.*`); wikilinks below are plain
// `[[Note N]]` text inside `bodyMd`, not materialized `Edge` rows.
//
// Determinism: every byte of every generated document is a pure function
// of (seed, generation order). Two `seed()` calls with the same `seed` and
// `Preset` produce NOTE/CHAT documents whose `(id, contentHash)` pairs are
// identical, and ATTACHMENT ("external") documents whose `contentHash`
// (bytes) is identical — see `SyntheticVaultTest` for why attachment *ids*
// are the one exception (`InMemoryVaultRepository.createAttachment` mints
// its own `UUID.randomUUID()` with no override hook, and this generator
// must not modify that file). This rules out `System.currentTimeMillis()`,
// unseeded `Random()`, and any other non-seeded randomness in this file.
// Document ids for notes/chats are minted locally as UUIDv7-shaped strings
// (same bit layout as `app.skein.core.vault.id.Uuid7`, `E2.I3`) but driven
// entirely by a seeded `kotlin.random.Random` and a synthetic incrementing
// millisecond counter rather than `SecureRandom`/`Clock.systemUTC()` — the
// real `Uuid7` object is intentionally not reused here since its random
// component is non-deterministic by design. `:testing` also has no
// dependency on `:core:vault`, only `:core:model`.
//
// `VaultRepository.createDocument`/`createAttachment` compute
// `Document.contentHash` themselves (SHA-256 of `bodyMd` or of the
// attachment bytes) from whatever content this file passes in, so
// reproducing the id and the content deterministically is sufficient for
// the whole `Document` to replay identically.

package us.aherrera.skein.testing.fixtures

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.NewMessage
import us.aherrera.skein.core.model.Role
import us.aherrera.skein.core.model.VaultRepository
import java.time.Instant
import kotlin.random.Random

/**
 * Deterministic synthetic vault fixture generator (`E10.I4`).
 *
 * ```
 * val repo = InMemoryVaultRepository()
 * SyntheticVault.seed(repo) // seed = 42L, size = Preset.LARGE (1000 docs)
 * ```
 */
public object SyntheticVault {
    /** Fixed default seed — `seed(repo)` with no arguments always replays the same vault. */
    public const val DEFAULT_SEED: Long = 42L

    /**
     * Named fixture sizes. `notes + chats + externals` is the total document
     * count (`Preset.LARGE.total == 1000`, matching the "1k-note vault" this
     * issue is named for).
     */
    public enum class Preset(
        public val notes: Int,
        public val chats: Int,
        public val externals: Int,
    ) {
        SMALL(40, 5, 5),
        MEDIUM(400, 30, 70),
        LARGE(800, 50, 150),
        ;

        public val total: Int get() = notes + chats + externals
    }

    /**
     * Seeds [repo] with [size] documents derived from [seed]. Blocking
     * (wraps the suspending `VaultRepository` calls in [runBlocking]) so it
     * is convenient to call from plain JUnit test setup as well as from
     * suspend contexts.
     */
    public fun seed(
        repo: VaultRepository,
        seed: Long = DEFAULT_SEED,
        size: Preset = Preset.LARGE,
    ): Unit =
        runBlocking {
            val content = Random(seed)
            val ids = IdMinter(seed)

            val noteTitles = createNotes(repo, content, ids, size.notes)
            createChats(repo, content, ids, size.chats, noteTitles)
            createExternals(repo, content, size.externals)
        }

    // ------------------------------------------------------------------
    // Notes
    // ------------------------------------------------------------------

    private suspend fun createNotes(
        repo: VaultRepository,
        rnd: Random,
        ids: IdMinter,
        count: Int,
    ): List<String> {
        val titles = (1..count).map { noteTitle(it) }
        // A link can point up to 10% past the real range so some wikilinks
        // dangle (bd `E10.I4`: "a wikilink graph ... some dangling links").
        val maxLinkTarget = (count + count / 10).coerceAtLeast(count + 1)
        var lastMarkerNoteIndex = -1
        var lastMarkerTerm: String? = null

        for (i in 1..count) {
            val rolledMarker =
                if (rnd.nextInt(MARKER_EVERY) == 0) Lexicon.markerTerms[rnd.nextInt(Lexicon.markerTerms.size)] else null
            // A note only paraphrases an earlier one when it didn't just roll
            // its own fresh marker term (mutually exclusive branches in
            // `buildNoteBody`).
            val isParaphrase = rolledMarker == null && lastMarkerTerm != null && rnd.nextInt(PARAPHRASE_EVERY) == 0
            val effectiveMarker = rolledMarker ?: if (isParaphrase) lastMarkerTerm else null
            val paraphraseOfIndex = if (isParaphrase) lastMarkerNoteIndex else null
            val tags = pickTags(rnd)
            val links = pickWikilinkTargets(rnd, maxLinkTarget)
            val body =
                buildNoteBody(
                    rnd = rnd,
                    marker = effectiveMarker,
                    tags = tags,
                    wikilinkTargets = links,
                    paraphraseOfIndex = paraphraseOfIndex,
                )
            repo.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = titles[i - 1],
                    bodyMd = body,
                    personaId = pickPersonaId(rnd),
                    frontmatter = noteFrontmatter(ids.next(), titles[i - 1], i, tags),
                    id = null,
                ),
            )
            if (rolledMarker != null) {
                lastMarkerNoteIndex = i
                lastMarkerTerm = rolledMarker
            }
        }
        return titles
    }

    private fun buildNoteBody(
        rnd: Random,
        marker: String?,
        tags: List<String>,
        wikilinkTargets: List<Int>,
        paraphraseOfIndex: Int?,
    ): String =
        buildString {
            appendLine("# ${sentenceCase(phrase(rnd, 2, 5))}")
            appendLine()
            repeat(rnd.nextInt(2, 5)) {
                appendLine(paragraph(rnd))
                appendLine()
            }
            appendLine("## Key points")
            repeat(rnd.nextInt(2, 5)) {
                appendLine("- ${sentenceCase(phrase(rnd, 3, 8))}.")
            }
            appendLine()
            if (rnd.nextInt(5) == 0) {
                appendLine("```kotlin")
                appendLine("val ${Lexicon.words[rnd.nextInt(Lexicon.words.size)]} = ${rnd.nextInt(1_000)}")
                appendLine("```")
                appendLine()
            }
            if (marker != null) {
                if (paraphraseOfIndex != null) {
                    appendLine(
                        "In other words, ${sentenceCase(phrase(rnd, 4, 9))} — related to $marker, " +
                            "see [[${noteTitle(paraphraseOfIndex)}]].",
                    )
                } else {
                    appendLine("This note discusses $marker in the context of ${phrase(rnd, 2, 4)}.")
                }
                appendLine()
            }
            if (tags.isNotEmpty()) {
                appendLine(tags.joinToString(" ") { "#$it" })
                appendLine()
            }
            for (target in wikilinkTargets) {
                appendLine("See also [[${noteTitle(target)}]].")
            }
        }

    // ------------------------------------------------------------------
    // Chats
    // ------------------------------------------------------------------

    private suspend fun createChats(
        repo: VaultRepository,
        rnd: Random,
        ids: IdMinter,
        count: Int,
        noteTitles: List<String>,
    ) {
        for (i in 1..count) {
            val title = "Chat $i"
            val chat =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.CHAT,
                        title = title,
                        bodyMd = null,
                        frontmatter = chatFrontmatter(ids.next(), title),
                    ),
                )
            val messageCount = rnd.nextInt(3, 6)
            repeat(messageCount) { turn ->
                val role = if (turn % 2 == 0) Role.USER else Role.ASSISTANT
                val referenced =
                    if (noteTitles.isNotEmpty() && rnd.nextInt(4) == 0) {
                        noteTitles[rnd.nextInt(noteTitles.size)]
                    } else {
                        null
                    }
                val content =
                    buildString {
                        append(sentenceCase(phrase(rnd, 5, 12)))
                        append('.')
                        if (referenced != null) {
                            append(" See [[$referenced]].")
                        }
                    }
                repo.appendMessage(chat.id, NewMessage(role = role, contentMd = content))
            }
        }
    }

    // ------------------------------------------------------------------
    // Externals (attachments — metadata only, bytes via the repo's
    // injected `AttachmentStore` fake)
    // ------------------------------------------------------------------

    /**
     * `VaultRepository.createAttachment` (`InMemoryVaultRepository`, `E0.I11`)
     * mints the document id itself via `UUID.randomUUID()` and has no
     * parameter to override it — unlike `createDocument`, there is no
     * frontmatter-`id` back door. Since this generator must not modify
     * `InMemoryVaultRepository`, external-document *ids* are the one place
     * this fixture cannot be byte-identical across runs; their *content*
     * (bytes, and therefore `contentHash`) is still fully deterministic
     * from [rnd]. `SyntheticVaultTest` accounts for this when asserting
     * replay determinism.
     */
    private suspend fun createExternals(
        repo: VaultRepository,
        rnd: Random,
        count: Int,
    ) {
        for (i in 1..count) {
            val mimeType = EXTERNAL_MIME_TYPES[i % EXTERNAL_MIME_TYPES.size]
            val bytes = ByteArray(rnd.nextInt(32, 512))
            rnd.nextBytes(bytes)
            repo.createAttachment(title = "External $i", mimeType = mimeType) { out -> out.write(bytes) }
        }
    }

    // ------------------------------------------------------------------
    // Frontmatter (canonical keys: `FrontmatterKeys`, `E0.I14`/`E2.I3`)
    // ------------------------------------------------------------------

    private fun noteFrontmatter(
        id: String,
        title: String,
        index: Int,
        tags: List<String>,
    ) = buildJsonObject {
        put(FrontmatterKeys.ID, JsonPrimitive(id))
        put(FrontmatterKeys.KIND, JsonPrimitive(DocumentKind.NOTE.db))
        put(FrontmatterKeys.TITLE, JsonPrimitive(title))
        put(FrontmatterKeys.CREATED, JsonPrimitive(instantFor(index).toString()))
        put(FrontmatterKeys.UPDATED, JsonPrimitive(instantFor(index).toString()))
        put(FrontmatterKeys.TAGS, JsonArray(tags.map { JsonPrimitive(it) }))
    }

    private fun chatFrontmatter(
        id: String,
        title: String,
    ) = buildJsonObject {
        put(FrontmatterKeys.ID, JsonPrimitive(id))
        put(FrontmatterKeys.KIND, JsonPrimitive(DocumentKind.CHAT.db))
        put(FrontmatterKeys.TITLE, JsonPrimitive(title))
    }

    private fun instantFor(index: Int): Instant = Instant.ofEpochMilli(BASE_EPOCH_MILLIS + index * 60_000L)

    // ------------------------------------------------------------------
    // Content helpers
    // ------------------------------------------------------------------

    private fun pickTags(rnd: Random): List<String> =
        List(rnd.nextInt(1, 4)) { Lexicon.tags[rnd.nextInt(Lexicon.tags.size)] }.distinct()

    private fun pickPersonaId(rnd: Random): String? =
        if (rnd.nextInt(3) == 0) Lexicon.personas[rnd.nextInt(Lexicon.personas.size)] else null

    private fun pickWikilinkTargets(
        rnd: Random,
        maxTarget: Int,
    ): List<Int> {
        val degree = powerLawDegree(rnd)
        return List(degree) { rnd.nextInt(1, maxTarget + 1) }
    }

    /** Mostly 0-2 out-links; occasional hub notes with much higher degree. */
    private fun powerLawDegree(rnd: Random): Int {
        val roll = rnd.nextDouble()
        return when {
            roll < 0.55 -> 0
            roll < 0.80 -> 1
            roll < 0.93 -> 2
            roll < 0.98 -> rnd.nextInt(3, 6)
            else -> rnd.nextInt(6, 13)
        }
    }

    private fun phrase(
        rnd: Random,
        minWords: Int,
        maxWords: Int,
    ): String {
        val n = rnd.nextInt(minWords, maxWords + 1)
        val words = mutableListOf<String>()
        repeat(n) {
            words +=
                if (rnd.nextInt(12) == 0) {
                    Lexicon.entities[rnd.nextInt(Lexicon.entities.size)]
                } else {
                    Lexicon.words[rnd.nextInt(Lexicon.words.size)]
                }
        }
        return words.joinToString(" ")
    }

    private fun sentenceCase(text: String): String = text.replaceFirstChar { it.uppercase() }

    private fun sentence(rnd: Random): String = "${sentenceCase(phrase(rnd, 6, 14))}."

    private fun paragraph(rnd: Random): String = List(rnd.nextInt(2, 5)) { sentence(rnd) }.joinToString(" ")

    private fun noteTitle(index: Int): String = "Note $index"

    private const val BASE_EPOCH_MILLIS: Long = 1_700_000_000_000L
    private const val MARKER_EVERY: Int = 6
    private const val PARAPHRASE_EVERY: Int = 5
    private val EXTERNAL_MIME_TYPES =
        listOf("application/pdf", "image/png", "text/plain", "application/octet-stream")

    // ------------------------------------------------------------------
    // Deterministic UUIDv7-shaped id minting
    // ------------------------------------------------------------------

    /**
     * Mints UUIDv7-shaped ids (same bit layout as `Uuid7`, `E2.I3`) from a
     * seeded [Random] and a synthetic, strictly-increasing millisecond
     * counter instead of `SecureRandom`/`Clock.systemUTC()`, so the same
     * [seed] always mints the same sequence of ids.
     */
    private class IdMinter(
        seed: Long,
    ) {
        private val random = Random(seed xor ID_STREAM_SALT)
        private var millis = BASE_EPOCH_MILLIS

        fun next(): DocId {
            millis += 1
            val counter = random.nextInt(0, COUNTER_MASK + 1)
            val versionNibble = 0x7000L
            val mostSigBits = (millis shl 16) or versionNibble or counter.toLong()

            val randB = random.nextLong() and RAND_B_MASK
            val variantBits = 0b10L shl 62
            val leastSigBits = variantBits or randB

            return toCanonicalHex(mostSigBits, leastSigBits)
        }

        private fun toCanonicalHex(
            mostSigBits: Long,
            leastSigBits: Long,
        ): String {
            val msbHex = mostSigBits.toULong().toString(16).padStart(16, '0')
            val lsbHex = leastSigBits.toULong().toString(16).padStart(16, '0')
            val hex = msbHex + lsbHex
            return buildString(36) {
                append(hex, 0, 8)
                append('-')
                append(hex, 8, 12)
                append('-')
                append(hex, 12, 16)
                append('-')
                append(hex, 16, 20)
                append('-')
                append(hex, 20, 32)
            }
        }

        private companion object {
            const val COUNTER_MASK = 0xFFF
            const val RAND_B_MASK = (1L shl 62) - 1
            const val ID_STREAM_SALT = 0x5EED_1D00_5EED_1D0L
        }
    }

    /** Fixed vocabulary the generator draws from — kept small and reviewable rather than a huge word list. */
    private object Lexicon {
        val words =
            listOf(
                "vault",
                "index",
                "chunk",
                "wikilink",
                "graph",
                "persona",
                "chat",
                "note",
                "attachment",
                "embedding",
                "retrieval",
                "citation",
                "sync",
                "backup",
                "recovery",
                "thermal",
                "device",
                "editor",
                "timeline",
                "frontmatter",
                "markdown",
                "tag",
                "entity",
                "ingest",
                "queue",
                "worker",
                "model",
                "token",
                "prompt",
                "context",
                "chunker",
                "reranker",
                "gguf",
                "onnx",
                "keystore",
                "cipher",
                "migration",
                "schema",
                "trigger",
                "cosine",
                "lexical",
                "semantic",
                "checklist",
                "draft",
                "recipe",
                "journal",
                "meeting",
                "project",
                "research",
                "review",
            )

        /** Rare, distinctive terms for lexical-match tests — never appear outside a marker sentence. */
        val markerTerms =
            listOf(
                "zamboni-latitude",
                "quokka-ledger",
                "umbral-cartography",
                "ferrous-lullaby",
                "obsidian-perihelion",
                "tessellate-brambling",
                "cinnabar-almanac",
                "glacial-mimeograph",
                "paprika-obelisk",
                "wisteria-quadrant",
                "sorrel-tesseract",
                "vermillion-cadence",
            )

        val tags =
            listOf(
                "project",
                "idea",
                "journal",
                "recipe",
                "travel",
                "research",
                "meeting",
                "reference",
                "draft",
                "archive",
                "todo",
                "review",
                "personal",
                "work",
                "reading",
                "code",
            )

        val personas = listOf("default", "work", "research", "personal")

        /** People / orgs / places sprinkled into sentences as named entities. */
        val entities =
            listOf(
                "Marisol Vance",
                "Declan Fitzgerald",
                "Priya Natarajan",
                "Oskar Lindqvist",
                "Amara Diallo",
                "Skein Labs",
                "Northwind Cooperative",
                "Fold Systems",
                "Rhea Robotics",
                "Lantern Guild",
                "Ashford",
                "Port Elara",
                "Cobalt Basin",
                "Merrow Heights",
                "New Talvera",
            )
    }
}

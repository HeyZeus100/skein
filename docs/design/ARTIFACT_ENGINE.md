# Artifact Engine — Architectural Sketch

**Status:** v2 architectural preview (2026-09-19). Not implemented; not in v1 scope. This document exists so the shape is captured while fresh; the full spec will live in a v2 design doc when work begins.

**Owner (design):** roadmap holder
**Owner (implementation):** TBD (post-v1)
**bd tracking issue:** `skein-bnwi`

---

## 1. Why this exists

Skein v1 treats DOCX/PDF as **output formats**: user asks the model to draft, model produces Markdown, Skein renders to DOCX or PDF. That is a strict subset of what a personal knowledge system should do with documents.

The Artifact Engine adds the other direction: **structured, constraint-preserving edit of documents the user already has**. A user drops a proposal into Skein and says "improve this but don't touch the quantities or prices"; Skein produces a reviewable diff with those constraints enforced deterministically, not just prompted.

The wedge is what pure LLM rewriting can't do: **hard fidelity guarantees on specified spans**. Every LLM drifts on numbers, dates, quoted spans, table cells, signature blocks. A deterministic engine layered on top of the LLM's proposals makes the output trustable for high-stakes documents — contracts, business plans, proposals, letters — where drift is a liability.

## 2. Pipeline shape

```
                       ARTIFACT ENGINE
                              │
             ┌────────────────┼────────────────┐
             │                │                │
          Markdown          DOCX              PDF
             │                │                │
             └────────────────┼────────────────┘
                              ▼
                     Structured Document IR
                              │
              ┌───────────────┼───────────────┐
              │               │               │
          Constraints     LLM reasoning     Retrieval
          (DSL)           (proposes         (RAG over
              │           patches only)     the vault)
              │               │               │
              └───────────────┼───────────────┘
                              ▼
                        Patch Bundle
                              │
                     Deterministic
                     enforcement layer
                              │
                              ▼
                     Reviewable Diff
                              │
                       User approves
                              │
                              ▼
                          Artifact
                     (round-trip to
                      original format)
```

**Key protocol invariant**: the LLM produces `PatchBundle` (typed JSON), never free-form document text. The deterministic layer rejects any patch that violates the constraint set. The user reviews the enforced diff before commit.

## 3. Structured Document IR

A Skein-owned tree structure representing any supported document format losslessly enough for round-trip.

### Design principles

- **Skein-owned**: no third-party document type crosses module boundaries. Import converters produce Skein IR; export converters consume Skein IR.
- **Stable IDs**: every node has a UUIDv7 assigned at import time. Patches reference nodes by ID, never by index or position (which are unstable across edits).
- **Attributed**: nodes carry provenance (source doc + span or coordinate), authored-by (user | model | deterministic-rewrite), and optional constraint tags.
- **Not Markdown**: MD is a serialization; the IR is richer (styles, tables, footnotes, math, comments, review-track). Round-trip lossy tightness varies by target format.

### Node kinds (initial cut)

```kotlin
sealed interface DocumentNode {
    val id: NodeId                 // UUIDv7
    val provenance: Provenance?    // where it came from
    val constraints: Set<Tag>      // 'preserved', 'protected-value', etc.
}

// Block-level
data class Heading(id, level: 1..6, inlines: List<Inline>, ...)
data class Paragraph(id, inlines: List<Inline>, ...)
data class ListNode(id, ordered: Bool, items: List<ListItem>, ...)
data class Table(id, columns: List<Col>, rows: List<Row>, caption: Inline?, ...)
data class CodeBlock(id, lang: String?, text: String, ...)
data class Blockquote(id, blocks: List<DocumentNode>, ...)
data class Divider(id, ...)
data class Section(id, title: Heading?, blocks: List<DocumentNode>, ...)
// v2.1+: Figure, Footnote, Math, Comment, ReviewTrack

// Inline
sealed interface Inline { val id: InlineId }
data class Text(inline)
data class Emphasis(kind: {bold, italic, underline, strike}, inlines)
data class Link(text: List<Inline>, href, title?)
data class WikiLink(target, alias?, heading?)
data class Code(text)
data class NumericSpan(text, kind: {money, quantity, date, percent}, canonical: BigDecimal?)
data class NamedSpan(text, category, ...)  // signature blocks, party names, etc.
```

`NumericSpan` and `NamedSpan` are the anchors for constraint enforcement — they carry the "what to preserve" identity that survives text rewriting.

### Marking

Converters mark nodes at import:
- Numeric spans by regex + locale rules → `NumericSpan`
- Party/name spans by NER (GLiNER already in `:embedder-service`) → `NamedSpan`
- Table cells, signature blocks, headings → carry their own kind

Users can annotate additional spans via the editor before invoking the engine ("preserve this sentence exactly", "preserve this whole paragraph").

## 4. Converters

Bidirectional. Each pair is its own module and shipping surface.

### 4.1 Markdown ↔ IR (`:core:doc:md`)

- Import: reuse existing `:core:markdown` parser (GFM), extended to emit `NumericSpan` / `NamedSpan` inline nodes via a post-pass.
- Export: pretty-printer over IR to canonical Markdown. Round-trip identity for pure-MD input.
- Loss profile: none for pure MD. Tables, footnotes, math preserve; comments and review-track become MD frontmatter or extension syntax.

### 4.2 DOCX ↔ IR (`:core:doc:docx`)

- Import: parse OOXML `word/document.xml` + styles + numbering + tables. Preserve the original package on the side (`.docx.original`) so unmodified sections round-trip byte-identical.
- Export: write OOXML from IR. When the source package is available, prefer targeted patches over the original XML (preserves author-specific style ids, custom XML, etc.); when generating from scratch, write minimal-package OOXML.
- Loss profile: initial cut supports headings, paragraphs, lists, tables, bold/italic/underline, hyperlinks, page breaks. Deferred: images, footnotes, comments, revisions, embedded objects, complex numbering, macros.
- **Not Apache POI**: too heavy (~15 MB) and copyleft-adjacent in transitives. Custom minimal reader/writer over OOXML.

### 4.3 PDF ↔ IR (`:core:doc:pdf`)

- Import: extract text + layout via a permissively-licensed PDF parser (candidates: PdfBox-Android, pdfium-android bindings). Build a semantic tree from layout (headings from font size / weight, paragraphs from block detection, tables from column detection).
- Export: NOT a full write path in v2. Skein produces annotated PDF only via a rendering intermediate (IR → MD → PDF via Android PrintManager, same as v1 export). Full PDF write is v3+.
- Loss profile: import is inherently lossy for complex PDFs (scientific papers with multi-column, ligatures, forms). OCR-quality scans are deferred to v3.
- Important: PDF import cannot promise round-trip. State this in the UI when a PDF is loaded.

## 5. Constraint DSL

The user (or a persona template) declares what must be preserved. Constraints are enforced deterministically, not prompted.

### Syntax (initial cut)

```yaml
preserve:
  # Preserve all NumericSpan nodes matching kind money or quantity
  - kind: money
  - kind: quantity

  # Preserve all NamedSpan nodes tagged as parties
  - named-span: party

  # Preserve specific spans by user annotation
  - marked: true

  # Preserve entire tables
  - block: table

  # Preserve any span matching a regex verbatim
  - regex: '\$[\d,]+(\.\d{2})?'
  - regex: 'due\s+by\s+\d{4}-\d{2}-\d{2}'

  # Preserve the headings ≥ level 2 (structure preserved, text editable at H1 only)
  - heading-level: '>= 2'

  # Preserve signature blocks (detected NER category)
  - named-span: signature

modify:
  # Explicitly opt-in modifiable sections
  - section: 'Introduction'
  - section: 'Executive Summary'

# Meta
never_add_new:
  - numeric-span: money  # LLM cannot introduce new dollar amounts
  - named-span: party    # LLM cannot introduce new parties

never_remove:
  - marked: true         # user-marked content cannot be deleted
```

Constraints are composed left-to-right with AND semantics; `modify` explicitly whitelists edit targets when the user wants to lock everything else.

### Enforcement layer

Given a PatchBundle from the LLM, for each patch:

```
for patch in bundle.patches:
    for constraint in constraints:
        if constraint.violates(patch):
            reject(patch, reason=constraint.name)
    if patch not rejected:
        stage(patch)

emit diff over staged patches → user review
```

Rejection is deterministic and reported: "patch 3 rejected: adds a new NumericSpan (money) which is denied by `never_add_new: money`". No fallback, no retry with a different phrasing, no LLM-mediated resolution.

## 6. LLM protocol

The LLM sees:

1. The document as a **stable-ID node stream**, not raw text. Prompt template:
   ```
   Document:
   [N1 heading level=1] "Proposal for X"
   [N2 paragraph] "We propose to deliver ..."
   [N3 numeric-span money] "$47,500"
   [N4 paragraph] " ... "
   ...
   
   Constraints:
   - Preserved: all money spans (never modify, never add new)
   - Modifiable: sections in Introduction and Executive Summary
   
   User request: "Improve this proposal but don't alter quantities or prices."
   ```

2. Retrieval context from the vault (if relevant notes exist), tagged as retrieved-data-not-instructions per `skein-q3r7`.

The LLM produces:

```json
{
  "patches": [
    {"op": "replace", "target": "N2", "new_inlines": [...], "rationale": "..."},
    {"op": "insert-after", "target": "N4", "new_node": {...}, "rationale": "..."},
    {"op": "delete", "target": "N7", "rationale": "..."}
  ]
}
```

Operations (initial cut): `replace`, `insert-before`, `insert-after`, `delete`, `wrap`, `unwrap`. All by node ID; no positional or text-search operations.

If the LLM emits free-form document text, the response is rejected wholesale (schema validation) and the request retried once with a stronger schema prompt. Second failure surfaces as "the model didn't return editable patches" and the user is offered to retry with a different model or refine the request.

## 7. Diff review UX

- Side-by-side (unfolded Fold) or before/after tabs (folded phone): original document on left, patched document on right, with color-coded changes
- Per-patch approve / reject controls
- "Approve all" only when zero constraint rejections
- Rejected-patch panel: shows LLM's proposed patches that violated constraints, with the specific reason; user can override individually (single-patch escape hatch, requires explicit tap per patch)
- Commit produces a new artifact — never overwrites the source. Source stays as an immutable prior revision in the vault; the artifact is a linked note with `[[source-doc-uuid]]`

## 8. Round-trip and provenance

- The applied artifact retains full node-level provenance: which nodes came from the source, which came from LLM patches (with the LLM's rationale), which came from deterministic rewrites
- The `documents` row for the artifact carries a `derived_from: [source_uuid]` link and a `patch_bundle_hash`
- Rendering the artifact in the editor shows provenance overlays (optional) so the user can inspect "which sentences did the model touch?"
- Export in the original format (DOCX from DOCX-sourced) is the default; export in any other format is available

## 9. Security considerations

- Constraints protect content, not the whole document. An attacker who controls the source document can still influence which constraints get suggested — always show the user the constraint set before running the engine
- Prompt-injection defense (`skein-q3r7`): the source document is data, not instructions. LLM must never derive tool calls or Intent URIs from source content
- Model file mutation while an engine run is in flight: same defenses as `skein-st1r` (mmap immutability, companion hashes)
- Never render LLM output as HTML/rich-content; only ever emit patches, and only the deterministic layer produces the final rendered artifact
- All engine runs are recorded in the (v2) audit log per `skein-2chp`: constraints, source hash, patch bundle, approved subset, resulting artifact hash

## 10. Phasing

**v2.0 (first Artifact Engine ship):**
- Markdown ↔ IR (only)
- Basic constraint DSL (preserve/modify, marked, block, kind)
- Patch protocol (replace/insert/delete only)
- Enforcement + diff review
- Provenance capture

**v2.1:**
- DOCX ↔ IR (minimal package, no images/footnotes/comments)
- PDF → IR (import; export stays via MD intermediate)
- Constraint DSL extensions (regex, heading-level, never_add_new)

**v2.2+:**
- DOCX images/footnotes/comments/revisions
- PDF direct rendering
- Constraint learning (agent proposes constraints from prior user annotations)
- Multi-document constraint templates ("Minera proposal template" applies its constraints to every doc opened under that template)

**v3+:**
- Scanned-PDF OCR ingest
- Comparison mode: two versions of the same document, engine highlights differences and suggests reconciliation patches
- Batch operation: apply the same LLM-driven edit to multiple documents with per-doc constraint checks

## 11. Non-goals

- **Office suite parity.** Skein doesn't compete with Word, Pages, LibreOffice. The Artifact Engine handles a narrow-but-deep slice: constraint-preserving edit of documents you already have, with hard fidelity guarantees.
- **Real-time collaborative editing.** No shared state between users. Multi-device sync (v2) syncs whole documents, not operational transforms.
- **General-purpose PDF authoring.** PDF is input-heavy, output-light in v2.
- **Constraint-satisfaction as a search problem.** Constraints reject; they don't guide the LLM. The LLM proposes, constraints filter. No constraint-guided sampling or grammar-constrained decoding in v2 (that's v3+).
- **Legal, medical, tax advice.** The engine helps *authors* draft with fidelity. It does not opine on document meaning. Skill authors (per `skein-q3r7`) must always defer domain judgment to human review.

## 12. Prior art (references, not dependencies)

- **Coccinelle** (spatch): semantic patches for C. Same shape: pattern-match → apply → verify. https://coccinelle.gitlabpages.inria.fr
- **RFC 6902 JSON Patch**: structured diff over JSON documents. IR patches use a similar operation vocabulary. https://datatracker.ietf.org/doc/html/rfc6902
- **Track Changes in Word**: propose-not-commit UX. Skein's diff review is a stripped-down version.
- **Google Docs suggestions**: same interaction model, cloud-hosted. Skein does it offline.
- **Anthropic's Constitutional AI**: constraint-satisfying LLM output. Different mechanism (RLHF-time constraints); same goal (LLM output that respects declared invariants).
- **Semantic scholar diff tools**: structural document comparison. Skein extends to constrained *editing*, not just viewing.

## 13. Open architectural questions (must resolve before v2.0 ship)

1. **IR persistence format** — is IR serialized separately, or always regenerated from source on load? Impacts vault storage and re-open latency.
2. **Patch identity across model swaps** — if the user swaps LLM mid-review, do previously-proposed patches survive? Recommend: yes, patches are model-agnostic once emitted; the review UX shows which model produced which patch.
3. **Constraint composition** — how do multiple templates layer? Recommend: strictest-wins for `preserve`, union for `modify`, but this needs a formal semantics.
4. **Undo/redo semantics** — the artifact is a new document. Do we retain the pre-approval draft state for user regret? Recommend: yes, save every user-facing engine run as a discardable draft in the vault until explicitly deleted.
5. **Constraint learning** — v2.2's "agent proposes constraints from prior annotations" needs a design; not blocking v2.0.

## 14. Implementation ordering suggestion

When v2 work starts, build in this order to minimize dead code:

1. Structured Document IR (types + provenance + IDs) — no format support yet
2. Markdown ↔ IR (round-trip identity tests)
3. Patch operation types + apply function
4. Basic constraint DSL + enforcement
5. LLM prompt template + JSON schema validator for PatchBundle
6. Diff review UI (Compose) — works on MD-only initially
7. Vault integration (artifact = new document with `derived_from` link). Vault access goes through the primitives defined in [`VAULT_TOOL_PRIMITIVES.md`](VAULT_TOOL_PRIMITIVES.md) — specifically `write_note` for the derived artifact, `patch_note` for constraint-preserving edits (with `PatchOp.baseRevision` gating drift), and `search_vault` for retrieval into constraint checks. E5/E7 impls of those primitives are prerequisites for step 7.
8. DOCX ↔ IR (minimal package)
9. PDF → IR (import only)
10. Extended constraints, patch operations, review UX polish

Every step must ship with its own tests before the next starts. v2.0 is when steps 1–7 are green and shippable. Steps 8–10 are v2.1.

---

## References to open bd issues that feed into this

- `skein-bnwi` — this design's tracking issue (P3, roadmap)
- `skein-fvne` — vault tool primitives (P1, v1-adjacent) — the Artifact Engine's engine calls these
- `skein-ojyi` — Hermes-style skill format (P3) — engine-invocation is exposed as skills
- `skein-q3r7` — skill authoring guardrails (P3) — the engine's LLM prompts inherit these
- `skein-fncp` — Intelligence Analyze/Compare/Synthesize (P3) — Compare mode is Artifact Engine over two docs
- `skein-2chp` — Security Capabilities + Audit (P3) — engine runs are audited events
- `skein-uo5n` — citation stability (P1) — engine outputs cite constraints and source spans; same stability discipline required

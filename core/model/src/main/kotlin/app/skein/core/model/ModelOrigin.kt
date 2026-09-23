// H0/H3 provenance vocabulary (`docs/design/SKEIN_HUB.md` §3.4, §3.6; beads
// `skein-cyq`, `skein-cwsl`). `:core:model` is pure Kotlin/JVM with no
// project dependencies, so this file — like `Inference.kt` — is on every
// process's classpath, and (per SKEIN_HUB.md §3.4) is exactly the file a
// future Hub build compiles a second time via a `srcDir` pointer, so that
// Core and Hub share one definition of the wire vocabulary with no Gradle
// edge between the two builds.
//
// `ImportSource` (the sealed `Bundled`/`Picked`/`HubOffer` type H0 amends
// `ModelManager.import` to take) is deliberately NOT here: `Bundled` carries
// a `ModelManifest` (`:core:inference`) and `Picked`/`HubOffer` carry an
// `android.net.Uri` (Android SDK), and this module has neither as a
// dependency — nor could it, given the module DAG (`:core:model` has zero
// project dependencies; `:core:inference` depends on `:core:model`, not the
// reverse). `ImportSource` therefore lives beside `ModelManager` in
// `core/inference/.../models/ModelManager.kt`, exactly where
// `docs/design/SKEIN_HUB.md` §3.6 puts it. What DOES belong here — because
// it is pure data with no such dependency — is [ModelOrigin] and
// [ArtifactOffer], and the registry's own row type, [ModelRecord].

package app.skein.core.model

/**
 * Where an imported model's bytes came from — never a trust verdict.
 * `ModelManager`'s digest gates and `ModelInspector.inspect` run identically
 * regardless of origin (`docs/design/SKEIN_HUB.md` §1.2: "a same-signature
 * sender may hand Core bytes; it may not hand Core a conclusion").
 *
 * Persisted as `models.origin`; [db] is the exact string migration
 * `009_model_origin.sql` writes, and the string every pre-existing row is
 * defaulted to by that migration (`DEFAULT 'document_picker'`) since every
 * model imported before this column existed arrived through the document
 * picker.
 */
public enum class ModelOrigin(
    public val db: String,
) {
    BUNDLED("bundled"),
    PICKED("document_picker"),
    HUB("hub"),
}

/**
 * Untrusted display hints a Hub artifact hand-off carries alongside its
 * `content://` URI (`docs/design/SKEIN_HUB.md` §3.4, §5). Pure data, no
 * behaviour: every field is a *hint* — sanitised and quarantined by
 * `ModelManager` before any of it reaches a manifest — and never
 * authoritative for `id`, `sha256`, `size_bytes`, `format` or
 * `capabilities`, which Core always derives itself.
 *
 * [transferDigest] is Hub's post-download SHA-256 of the artifact. It is
 * compared exactly once, at import, for transfer integrity only
 * (`TransferDigestMismatch` on a mismatch) — a distinct concept from the
 * manifest's own content-trust digest, which Core computes independently
 * while it copies the bytes.
 *
 * `ImportSource.HubOffer` carries this until `skein-twn1` (H5) implements
 * the Hub path; until then every `HubOffer` import refuses with
 * `ModelManager.ImportRefusal.Unsupported`.
 */
public data class ArtifactOffer(
    public val declaredSizeBytes: Long,
    public val transferDigest: String? = null,
    public val displayName: String? = null,
    public val licenseSpdx: String? = null,
    public val licenseUrl: String? = null,
    public val sourceUrl: String? = null,
    public val sourceRevision: String? = null,
)

/**
 * The [ModelRegistry]'s persisted row: a [Model] plus the provenance columns
 * migration `009_model_origin.sql` added.
 *
 * Wraps [model] rather than duplicating its fields, so the two representations
 * cannot drift apart: [toModel] is the identity projection `InferenceEngine.load`
 * and every existing `Model` consumer already expects, and every registry
 * consumer that only cares about "what does the engine load" can ignore the
 * provenance fields entirely.
 *
 * [blake3] is the post-mmap digest recorded at import time (deviation note:
 * this column was planned for its own migration 004, reserved by
 * `docs/VAULT_FORMAT.md` §7 for `004_post_mmap_blake3`; it is carried in
 * migration `009_model_origin.sql` instead — see that file's header for why).
 * Null only for a row written before this bead landed.
 */
public data class ModelRecord(
    public val model: Model,
    public val blake3: String? = null,
    public val origin: ModelOrigin = ModelOrigin.PICKED,
    public val sourceUrl: String? = null,
    public val sourceRevision: String? = null,
    public val licenseSpdx: String? = null,
) {
    public fun toModel(): Model = model
}

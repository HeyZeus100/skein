// skein-cyq, H0 amendment 2 (docs/design/SKEIN_HUB.md §3.6): `ModelManager`'s
// three entry shapes — one pipeline, three sources of bytes. Lives here
// rather than in `:core:model` (see `core/model/.../ModelOrigin.kt`'s header
// for the DAG reason): `Bundled` carries this package's own `ModelManifest`,
// and `Picked`/`HubOffer` carry `android.net.Uri` — `:core:model` is pure
// Kotlin/JVM with no Android SDK and no dependency on `:core:inference`, so
// neither type could appear in a sealed interface declared there.

package app.skein.core.inference.models

import android.net.Uri
import app.skein.core.model.ArtifactOffer

/** The three ways a model's bytes reach [ModelManager.import]. */
public sealed interface ImportSource {
    /**
     * A shipped default model (`app/src/main/assets/models/`, `*.skein.json`),
     * CI-validated by `tools/ci/validate-manifests.py`. The manifest's own
     * `id` is used as-is — never Core-reassigned, unlike [Picked]/[HubOffer].
     */
    public data class Bundled(
        val manifest: ModelManifest,
    ) : ImportSource

    /** A file the user chose through the system document picker. No manifest travels with it. */
    public data class Picked(
        val uri: Uri,
    ) : ImportSource

    /**
     * A Hub-initiated hand-off (`docs/design/SKEIN_HUB.md` §2-§5). Refused
     * with [ModelManager.ImportRefusal.Unsupported] until `skein-twn1` (H5)
     * implements the transfer-digest cross-check and hint quarantine this
     * bead's scope does not cover — the sealed member exists now so H5 is
     * an addition to this file, not a second amendment to it.
     */
    public data class HubOffer(
        val uri: Uri,
        val offer: ArtifactOffer,
    ) : ImportSource
}

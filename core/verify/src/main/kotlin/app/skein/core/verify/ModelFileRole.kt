// skein-nxk (E4.I3, coordinator decision skein-hiwb): `ModelFileRole` moved
// here out of `:core:inference`'s `ModelManifest.kt`.
//
// Why it moved. The role is a *field type* of seven `ModelVerification`
// refusals and of `PinnedModel.companions`, and those types must be reachable
// from `:inference-service`, which the isolation guard forbids from depending
// on `:core:inference` (an Android library). A type the verifier's own
// vocabulary is expressed in is verification vocabulary, not manifest-parser
// vocabulary, so it belongs on this side of the boundary. `:core:inference`
// depends on `:core:verify` and goes on using the same enum under the same
// name; `ModelManifest`, `ManifestBinding` and `ImmutableModelStore` stay where
// they were.

package app.skein.core.verify

import app.skein.core.model.CompanionRole

/** Role of one file inside a model manifest. `main` is the mmap'd model itself; the rest are companions. */
enum class ModelFileRole(
    val wire: String,
) {
    MAIN("main"),
    MMPROJ("mmproj"),
    TOKENIZER("tokenizer"),
    TOKENIZER_CONFIG("tokenizer_config"),
    CONFIG("config"),
    GENERATION_CONFIG("generation_config"),
    LICENSE("license"),
    SPECIAL_TOKENS_MAP("special_tokens_map"),
    ;

    /**
     * The `:core:model` contract role this manifest role maps onto, or null
     * for [MAIN] (which is `Model.path`, not a companion).
     */
    val companionRole: CompanionRole?
        get() =
            when (this) {
                MAIN -> null
                MMPROJ -> CompanionRole.MMPROJ
                TOKENIZER -> CompanionRole.TOKENIZER
                TOKENIZER_CONFIG -> CompanionRole.TOKENIZER_CONFIG
                CONFIG -> CompanionRole.CONFIG
                GENERATION_CONFIG -> CompanionRole.GENERATION_CONFIG
                LICENSE -> CompanionRole.LICENSE
                SPECIAL_TOKENS_MAP -> CompanionRole.SPECIAL_TOKENS_MAP
            }

    companion object {
        fun fromWire(wire: String): ModelFileRole? = entries.firstOrNull { it.wire == wire }
    }
}

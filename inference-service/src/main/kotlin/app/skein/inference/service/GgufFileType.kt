// H1 (bd `skein-91yy`): `general.file_type`, rendered.
//
// `docs/design/SKEIN_HUB.md` §3.3 types `ModelInspection.quantization` as
// "general.file_type, rendered". The GGUF key holds a `llama_ftype` ordinal,
// and `llama_model_meta_val_str` — the only accessor the existing JNI surface
// has — formats it as its decimal digits, so without this table the field
// would reach the user as "15".
//
// THIS IS NOT A GGUF PARSER. It never sees a byte of the file: llama.cpp read
// the metadata, llama.cpp validated it, and this maps one number llama.cpp
// reported to the name llama.cpp's own `llama_ftype` enum gives it
// (`third_party/llama.cpp/include/llama.h`, pinned commit in
// `native/llama/PINNED_COMMIT`). Spec §17's "do not invent an independent
// incomplete parser" is about structure, not about naming an enum value.
//
// UNKNOWN VALUES ARE RENDERED, NOT DROPPED. A GGUF quantised by a newer
// llama.cpp than this table knows — or a hostile file stating an ordinal that
// means nothing — comes back as `ftype <n>` rather than null, so "the file
// says something we do not recognise" stays distinguishable from "the file
// says nothing". The value is display-only either way: whether the quant is
// actually loadable is decided by the load inside `inspect`, not here (§3.3's
// structural-checks table).

package app.skein.inference.service

/** `llama_ftype` ordinal → the name llama.cpp knows it by. */
internal object GgufFileType {
    /**
     * Renders the raw `general.file_type` value [raw] as a quantisation name.
     *
     * @return the `llama_ftype` name, `ftype <n>` for an ordinal this build
     *   does not know, or null when [raw] is not a number at all — the GGUF
     *   declared the key with some other type, and there is nothing to render.
     */
    fun render(raw: String): String? {
        val ordinal = raw.trim().toIntOrNull() ?: return null
        return NAMES[ordinal] ?: "ftype $ordinal"
    }

    /**
     * `enum llama_ftype` as of the pinned llama.cpp. The gaps (4, 5, 6, 33,
     * 34, 35) are values llama.cpp has removed; they are absent here on
     * purpose, so such a file renders as `ftype 5` rather than claiming a
     * format this build cannot load.
     */
    private val NAMES: Map<Int, String> =
        mapOf(
            0 to "F32",
            1 to "F16",
            2 to "Q4_0",
            3 to "Q4_1",
            7 to "Q8_0",
            8 to "Q5_0",
            9 to "Q5_1",
            10 to "Q2_K",
            11 to "Q3_K_S",
            12 to "Q3_K_M",
            13 to "Q3_K_L",
            14 to "Q4_K_S",
            15 to "Q4_K_M",
            16 to "Q5_K_S",
            17 to "Q5_K_M",
            18 to "Q6_K",
            19 to "IQ2_XXS",
            20 to "IQ2_XS",
            21 to "Q2_K_S",
            22 to "IQ3_XS",
            23 to "IQ3_XXS",
            24 to "IQ1_S",
            25 to "IQ4_NL",
            26 to "IQ3_S",
            27 to "IQ3_M",
            28 to "IQ2_S",
            29 to "IQ2_M",
            30 to "IQ4_XS",
            31 to "IQ1_M",
            32 to "BF16",
            36 to "TQ1_0",
            37 to "TQ2_0",
            38 to "MXFP4_MOE",
            39 to "NVFP4",
            40 to "Q1_0",
            41 to "Q2_0",
            1024 to "GUESSED",
        )
}

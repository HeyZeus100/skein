package app.skein.inference.service

import java.security.MessageDigest

/** Test-only artifact contract. It cannot configure a production tokenizer, template or stop policy. */
internal data class SyntheticParityControls(
    val profile: String,
    val modelSha256: String,
    val modelSize: Long,
    val templateSha256: String,
    val tokenizerMetadataSha256: String,
    val vocabularySize: Int,
    val architecture: String,
    val modelName: String,
    val literal: Control,
    val eog: List<Control>,
    val declaredEosId: Int,
) {
    data class Control(
        val spelling: String,
        val id: Int,
        val type: Int,
    )

    fun validate(
        actualModelHash: String,
        actualSize: Long,
        actualTemplate: String,
        actualArchitecture: String,
        actualName: String,
        actualEosId: Int?,
    ) {
        require(listOf(modelSha256, templateSha256, tokenizerMetadataSha256).all { it.matches(SHA256) })
        require(modelSha256 == actualModelHash && modelSize > 0 && modelSize == actualSize) { "model binding mismatch" }
        require(templateSha256 == sha256(actualTemplate.toByteArray(Charsets.UTF_8))) { "template binding mismatch" }
        require(architecture == actualArchitecture && modelName == actualName) { "model metadata mismatch" }
        val (start, end) =
            when (profile) {
                "chatml" -> {
                    require(architecture in setOf("llama", "qwen2")) { "unsupported ChatML architecture" }
                    "<|im_start|>" to "<|im_end|>"
                }
                "gemma4-e4b" -> {
                    require(
                        architecture == "gemma4" && E4B_NAME.containsMatchIn(modelName),
                    ) { "E4B identity unavailable" }
                    "<|turn>" to "<turn|>"
                }
                else -> error("unsupported control profile")
            }
        require(actualTemplate.contains(start) && actualTemplate.contains(end)) { "profile template controls absent" }
        require(vocabularySize in 1..2_000_000 && eog.size in 1..2)
        val controls = listOf(literal) + eog
        require(
            controls.all {
                it.id in 0 until vocabularySize &&
                    it.type == 3 &&
                    it.spelling.toByteArray().size in 1..128
            },
        )
        require(
            controls.map { it.id }.distinct().size == controls.size &&
                controls.map { it.spelling }.distinct().size == controls.size,
        )
        require(literal.spelling == start && eog.first().spelling == end) { "unsupported profile control spelling" }
        require(declaredEosId == actualEosId && eog.any { it.id == declaredEosId }) { "declared EOS mismatch" }
    }

    fun verifyNativeControl(
        control: Control,
        ids: IntArray,
        isEog: (Int) -> Boolean,
        expectedEog: Boolean,
    ) {
        require(ids.contentEquals(intArrayOf(control.id))) { "native control ID mismatch" }
        require(isEog(control.id) == expectedEog) { "native EOG classification failed" }
    }

    companion object {
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val E4B_NAME = Regex("(?:^|[^a-z0-9])gemma[ ._-]*4[ ._-]*e4b(?:$|[^a-z0-9])", RegexOption.IGNORE_CASE)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}

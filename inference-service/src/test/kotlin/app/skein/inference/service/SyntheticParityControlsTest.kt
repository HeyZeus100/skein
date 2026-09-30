package app.skein.inference.service

import org.junit.Assert.assertThrows
import org.junit.Test

class SyntheticParityControlsTest {
    private val template = "<|turn>{{ content }}<turn|>"
    private val contract =
        SyntheticParityControls(
            "gemma4-e4b",
            "a".repeat(64),
            100,
            SyntheticParityControls.sha256(template.toByteArray()),
            "b".repeat(64),
            5,
            "gemma4",
            "Gemma 4 E4B it",
            SyntheticParityControls.Control("<|turn>", 2, 3),
            listOf(SyntheticParityControls.Control("<turn|>", 3, 3), SyntheticParityControls.Control("<eos>", 1, 3)),
            1,
        )

    private fun validate(value: SyntheticParityControls = contract) =
        value.validate("a".repeat(64), 100, template, "gemma4", "Gemma 4 E4B it", 1)

    @Test
    fun explicit_synthetic_contract_accepts_matching_identity() {
        validate()
    }

    @Test
    fun changed_model_size_template_or_metadata_is_rejected() {
        val changes =
            listOf(
                contract.copy(modelSha256 = "c".repeat(64)),
                contract.copy(modelSize = 99),
                contract.copy(templateSha256 = "c".repeat(64)),
                contract.copy(architecture = "gemma3"),
                contract.copy(modelName = "Gemma 4 E2B it"),
                contract.copy(tokenizerMetadataSha256 = "upstream-pointer"),
            )
        changes.forEach { assertThrows(IllegalArgumentException::class.java) { validate(it) } }
    }

    @Test
    fun unrecognized_profiles_legacy_spellings_and_unbounded_controls_are_rejected() {
        assertThrows(IllegalStateException::class.java) { validate(contract.copy(profile = "inferred")) }
        val changes =
            listOf(
                contract.copy(vocabularySize = 2_000_001),
                contract.copy(vocabularySize = 0),
                contract.copy(literal = contract.literal.copy(id = 5)),
                contract.copy(literal = contract.literal.copy(id = -1)),
                contract.copy(literal = contract.literal.copy(type = 1)),
                contract.copy(literal = contract.literal.copy(spelling = "<start_of_turn>")),
                contract.copy(eog = emptyList()),
                contract.copy(eog = contract.eog + contract.eog.first()),
                contract.copy(eog = listOf(contract.literal)),
                contract.copy(declaredEosId = 3),
                contract.copy(eog = listOf(contract.eog.first())),
                contract.copy(eog = listOf(contract.eog.first(), contract.eog.last().copy(spelling = "x".repeat(129)))),
            )
        changes.forEach { assertThrows(IllegalArgumentException::class.java) { validate(it) } }
    }

    @Test
    fun native_singleton_id_and_eog_must_match_metadata_receipt() {
        val eog = contract.eog.first()
        contract.verifyNativeControl(eog, intArrayOf(3), { it == 3 }, true)
        contract.verifyNativeControl(contract.literal, intArrayOf(2), { false }, false)
        for (ids in listOf(intArrayOf(), intArrayOf(3, 3), intArrayOf(4))) {
            assertThrows(
                IllegalArgumentException::class.java,
            ) { contract.verifyNativeControl(eog, ids, { true }, true) }
        }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { contract.verifyNativeControl(eog, intArrayOf(3), { false }, true) }
    }

    @Test
    fun matching_template_digest_alone_cannot_qualify_legacy_template() {
        val old = "<start_of_turn>{{ content }}<end_of_turn>"
        val value = contract.copy(templateSha256 = SyntheticParityControls.sha256(old.toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            value.validate("a".repeat(64), 100, old, "gemma4", "Gemma 4 E4B it", 1)
        }
    }

    @Test
    fun explicit_chatml_profile_remains_usable_with_declared_turn_end_eos() {
        val chatml = "<|im_start|>{{ content }}<|im_end|>"
        val value =
            contract.copy(
                profile = "chatml",
                architecture = "llama",
                modelName = "Synthetic ChatML",
                templateSha256 = SyntheticParityControls.sha256(chatml.toByteArray()),
                literal = contract.literal.copy(spelling = "<|im_start|>"),
                eog = listOf(SyntheticParityControls.Control("<|im_end|>", 3, 3)),
                declaredEosId = 3,
            )
        value.validate("a".repeat(64), 100, chatml, "llama", "Synthetic ChatML", 3)
    }
}

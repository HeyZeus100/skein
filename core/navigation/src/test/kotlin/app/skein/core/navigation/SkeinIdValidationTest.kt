package app.skein.core.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** SECURITY_REVIEW_D7.md M1, M2 (§7, AL-06): only canonical UUIDs become ids; a rejection never echoes the input. */
class SkeinIdValidationTest {
    private val v7 = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b"

    @Test
    fun `accepts canonical UUIDv7 and v4, and every RFC 9562 version 1-8`() {
        assertThat(SkeinId.parse(v7)?.value).isEqualTo(v7)
        assertThat(SkeinId.parse("4b1c9d3e-2f6a-4c8b-9d0e-1a2b3c4d5e6f")).isNotNull()
        for (version in '1'..'8') {
            val id = "018f2b6e-6c3a-${version}c3e-af2a-6b1e2d3c4a5b"
            assertWithMessage("version $version").that(SkeinId.parse(id)).isNotNull()
        }
        for (variant in "89ab") assertThat(SkeinId.parse("018f2b6e-6c3a-7c3e-${variant}f2a-6b1e2d3c4a5b")).isNotNull()
    }

    @Test
    fun `rejects everything that is not a lowercase canonical UUID`() {
        val rejected =
            listOf(
                v7.uppercase(),
                "018F2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b",
                "{$v7}",
                "urn:uuid:$v7",
                " $v7",
                "$v7 ",
                "$v7\n",
                "\t$v7",
                v7.replace("-", ""),
                v7.replace('-', '_'),
                v7 + "0",
                v7.dropLast(1),
                Fx.TAG_NODE,
                Fx.TITLE_NODE,
                Fx.ENTITY_NODE,
                Fx.FOREIGN_NOTE,
                Fx.MODEL_SLUG,
                "project-falcon-acquisition",
                "sentinel-7f3a",
                "",
                "00000000-0000-0000-0000-000000000000",
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "018f2b6e-6c3a-0c3e-8f2a-6b1e2d3c4a5b",
                "018f2b6e-6c3a-9c3e-8f2a-6b1e2d3c4a5b",
                "018f2b6e-6c3a-7c3e-cf2a-6b1e2d3c4a5b",
                "018f2b6e-6c3a-7c3e-7f2a-6b1e2d3c4a5b",
                // Fullwidth and Arabic-Indic digits look like hex to a human, not to the regex.
                "０18f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b",
                "٠18f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b",
                "../" + v7.drop(3),
                "a".repeat(1 shl 20),
                v7.repeat(1 shl 15),
            )
        for (raw in rejected) {
            assertWithMessage("parse(${raw.take(40)})").that(SkeinId.parse(raw)).isNull()
            assertThat(SkeinId.isCanonical(raw)).isFalse()
            val error = runCatching { SkeinId.of(raw) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            // M13: the rejected value never reaches the message.
            assertThat(error!!.message).isEqualTo("not a canonical id")
        }
        assertThat(SkeinId.parse(null)).isNull()
    }

    @Test
    fun `random handles are canonical v4 and distinct`() {
        val ids = List(200) { SkeinId.random() }
        assertThat(ids.toSet()).hasSize(200)
        for (id in ids) {
            assertThat(SkeinId.isCanonical(id.value)).isTrue()
            assertThat(id.value[14]).isEqualTo('4')
        }
    }

    @Test
    fun `equality is by value and toString is redacted`() {
        assertThat(SkeinId.of(v7)).isEqualTo(SkeinId.parse(v7))
        assertThat(SkeinId.of(v7).hashCode()).isEqualTo(SkeinId.of(v7).hashCode())
        assertThat(SkeinId.of(v7).toString()).doesNotContain(v7)
        assertThat("$v7 ${SkeinId.of(v7)}").isEqualTo("$v7 SkeinId(redacted)")
    }
}

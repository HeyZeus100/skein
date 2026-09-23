// H1 (bd `skein-91yy`): the `general.file_type` renderer.
//
// The input is a string llama.cpp formatted out of an attacker-controlled GGUF
// key, so the interesting cases are not the well-formed ones: an ordinal this
// build has never heard of, a removed one, and a value that is not a number at
// all must each produce something a UI can show without lying about it.

package app.skein.inference.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GgufFileTypeTest {
    @Test
    fun aKnownOrdinalRendersAsItsQuantisationName() {
        assertThat(GgufFileType.render("15")).isEqualTo("Q4_K_M")
    }

    @Test
    fun anUnquantisedModelRendersAsItsFloatFormat() {
        assertThat(GgufFileType.render("1")).isEqualTo("F16")
    }

    @Test
    fun theLastOrdinalThisBuildKnowsStillRenders() {
        assertThat(GgufFileType.render("41")).isEqualTo("Q2_0")
    }

    @Test
    fun anOrdinalFromANewerLlamaCppIsReportedRatherThanDropped() {
        assertThat(GgufFileType.render("4242")).isEqualTo("ftype 4242")
    }

    @Test
    fun anOrdinalLlamaCppHasRemovedIsNotClaimedAsAFormat() {
        // 5 was Q4_2; support has been removed, and naming it would suggest
        // this build can load a file it cannot.
        assertThat(GgufFileType.render("5")).isEqualTo("ftype 5")
    }

    @Test
    fun surroundingWhitespaceDoesNotHideTheOrdinal() {
        assertThat(GgufFileType.render(" 18 ")).isEqualTo("Q6_K")
    }

    @Test
    fun aValueThatIsNotANumberRendersAsNothing() {
        assertThat(GgufFileType.render("Q4_K_M")).isNull()
    }

    @Test
    fun anEmptyValueRendersAsNothing() {
        assertThat(GgufFileType.render("")).isNull()
    }
}

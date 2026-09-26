// skein-xtov.23.16 (UT-2) — one runnable check that `UxSpec.nameSuffix`
// (and therefore every `captureUx` file name) actually matches the §3.5
// grammar: `<state-id>__<theme>__fs<NNN>[__<mod>].png`. A typo here would
// silently rename every golden on the next record.
package app.skein.testing.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UxSpecNamingTest {
    // docs/ux/UX_TEST_PLAN.md §3.5: theme := light | dark; NNN := 100 | 150 | 200.
    private val grammar = Regex("^__(light|dark)__fs(100|150|200)$")

    @Test
    fun nameSuffixMatchesTheNamingGrammar() {
        for (dark in listOf(false, true)) {
            for (fontScale in listOf(1f, 1.5f, 2f)) {
                val spec = UxSpec(SkeinDevice.PHONE, dark = dark, fontScale = fontScale)
                assertThat(spec.nameSuffix).matches(grammar.pattern)
            }
        }
    }

    @Test
    fun defaultsAreNotOmitted() {
        // Unlike the spike's conditional `-dark`/`-font150`, light/fs100 is
        // spelled out too (§3.5's own example: `chat-activity-starting__light__fs100.png`).
        val spec = UxSpec(SkeinDevice.PHONE, dark = false, fontScale = 1f)
        assertThat(spec.nameSuffix).isEqualTo("__light__fs100")
    }
}

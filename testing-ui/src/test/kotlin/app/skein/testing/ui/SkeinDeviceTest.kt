// skein-xtov.23.14 (UT-0) — a table this precise (12 measured qualifier
// strings) needs one check that a typo or a copy-paste slip trips: every
// `dir`/`qualifiers` pair is unique, and the qualifier string's own w/h dp
// actually match what the key name promises.
package app.skein.testing.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkeinDeviceTest {
    @Test
    fun everyKeyAndQualifierStringIsUnique() {
        val dirs = SkeinDevice.entries.map { it.dir }
        val qualifiers = SkeinDevice.entries.map { it.qualifiers }
        assertThat(dirs).containsNoDuplicates()
        assertThat(qualifiers).containsNoDuplicates()
        assertThat(dirs).hasSize(12)
    }

    @Test
    fun qualifierWidthAndHeightMatchTheMeasuredTable() {
        // docs/ux/UX_TEST_PLAN.md §3.1 — the full device matrix.
        val expected =
            mapOf(
                "phone" to ("w360dp-h800dp-port-xhdpi" to (360 to 800)),
                "split-320" to ("w320dp-h1007dp-port-330dpi" to (320 to 1007)),
                "fold-outer-443" to ("w443dp-h994dp-port-390dpi" to (443 to 994)),
                "fold-outer-524" to ("w524dp-h1175dp-port-330dpi" to (524 to 1175)),
                "fold-outer-443-land" to ("w994dp-h443dp-land-390dpi" to (994 to 443)),
                "fold-outer-524-land" to ("w1175dp-h524dp-land-330dpi" to (1175 to 524)),
                "fold-inner-852" to ("w852dp-h883dp-port-390dpi" to (852 to 883)),
                "fold-inner-852-land" to ("w883dp-h852dp-land-390dpi" to (883 to 852)),
                "fold-inner-1007" to ("w1007dp-h1043dp-port-330dpi" to (1007 to 1043)),
                "fold-inner-1007-land" to ("w1043dp-h1007dp-land-330dpi" to (1043 to 1007)),
                "medium-791" to ("w791dp-h820dp-port-420dpi" to (791 to 820)),
                "large-1280" to ("w1280dp-h800dp-land-xhdpi" to (1280 to 800)),
            )
        assertThat(SkeinDevice.entries).hasSize(expected.size)
        for (device in SkeinDevice.entries) {
            val (qualifiers, wh) = expected.getValue(device.dir)
            assertThat(device.qualifiers).isEqualTo(qualifiers)
            val w = Regex("w(\\d+)dp").find(device.qualifiers)!!.groupValues[1].toInt()
            val h = Regex("h(\\d+)dp").find(device.qualifiers)!!.groupValues[1].toInt()
            assertThat(w to h).isEqualTo(wh)
        }
    }
}

package app.skein.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises [groupedBySpdx]'s grouping/sorting/fallback rules used by [AboutScreen]'s section list. */
class LicenseGroupingTest {
    @Test
    fun `groups entries by their license field`() {
        val entries =
            listOf(
                entry(name = "a:a", license = "MIT"),
                entry(name = "b:b", license = "Apache-2.0"),
                entry(name = "c:c", license = "MIT"),
            )

        val groups = entries.groupedBySpdx()

        assertEquals(listOf("Apache-2.0", "MIT"), groups.map { it.spdxId })
        assertEquals(listOf("a:a", "c:c"), groups.single { it.spdxId == "MIT" }.entries.map { it.name })
    }

    @Test
    fun `sorts groups alphabetically case-insensitively`() {
        val entries =
            listOf(
                entry(name = "z:z", license = "zlib"),
                entry(name = "b:b", license = "BSD-3-Clause"),
                entry(name = "a:a", license = "Apache-2.0"),
            )

        val groups = entries.groupedBySpdx()

        assertEquals(listOf("Apache-2.0", "BSD-3-Clause", "zlib"), groups.map { it.spdxId })
    }

    @Test
    fun `sorts entries within a group by name case-insensitively`() {
        val entries =
            listOf(
                entry(name = "Zeta", license = "MIT"),
                entry(name = "alpha", license = "MIT"),
                entry(name = "Beta", license = "MIT"),
            )

        val groups = entries.groupedBySpdx()

        assertEquals(listOf("alpha", "Beta", "Zeta"), groups.single().entries.map { it.name })
    }

    @Test
    fun `files entries with an UNKNOWN license under the Unknown group instead of dropping them`() {
        val entries =
            listOf(
                entry(name = "a:a", license = "UNKNOWN"),
                entry(name = "b:b", license = "MIT"),
            )

        val groups = entries.groupedBySpdx()

        assertEquals(listOf("MIT", UNKNOWN_LICENSE_GROUP), groups.map { it.spdxId })
        assertEquals(listOf("a:a"), groups.single { it.spdxId == UNKNOWN_LICENSE_GROUP }.entries.map { it.name })
    }

    @Test
    fun `files entries with a blank license under the Unknown group`() {
        val entries = listOf(entry(name = "a:a", license = ""))

        val groups = entries.groupedBySpdx()

        assertEquals(UNKNOWN_LICENSE_GROUP, groups.single().spdxId)
    }

    @Test
    fun `never drops an entry — total entry count across groups matches the input`() {
        val entries =
            listOf(
                entry(name = "a:a", license = "MIT"),
                entry(name = "b:b", license = "Apache-2.0"),
                entry(name = "c:c", license = "UNKNOWN"),
                entry(name = "d:d", license = "BSD-3-Clause"),
                entry(name = "e:e", license = "OFL-1.1"),
            )

        val groups = entries.groupedBySpdx()

        assertEquals(entries.size, groups.sumOf { it.entries.size })
    }

    @Test
    fun `an empty entry list produces no groups`() {
        val groups = emptyList<LicenseEntry>().groupedBySpdx()

        assertEquals(emptyList<LicenseGroup>(), groups)
    }

    private fun entry(
        name: String,
        license: String,
        version: String = "1.0.0",
        url: String = "",
    ) = LicenseEntry(name = name, version = version, license = license, url = url)
}

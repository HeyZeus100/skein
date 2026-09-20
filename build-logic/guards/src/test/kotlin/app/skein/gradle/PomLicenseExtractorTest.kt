package app.skein.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PomLicenseExtractorTest {

    private val aliases = mapOf(
        "Apache-2.0" to listOf(
            "Apache License, Version 2.0",
            "The Apache Software License, Version 2.0",
            "Apache 2.0",
        ),
        "MIT" to listOf("MIT License", "The MIT License"),
        "LGPL-2.1" to listOf("GNU Lesser General Public License, Version 2.1"),
    )
    private val allowlist = setOf("Apache-2.0", "MIT")
    private val extractor = PomLicenseExtractor(aliases, allowlist)

    private fun pomWithLicenses(vararg licenses: Pair<String, String>): File {
        val dir = Files.createTempDirectory("pom-license-extractor-test").toFile()
        val pom = File(dir, "artifact.pom")
        val licensesXml = licenses.joinToString("\n") { (name, url) ->
            """
            |    <license>
            |      <name>$name</name>
            |      <url>$url</url>
            |    </license>
            """.trimMargin()
        }
        pom.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project>
            |  <licenses>
            |$licensesXml
            |  </licenses>
            |</project>
            """.trimMargin()
        )
        return pom
    }

    // --- normalize() -------------------------------------------------

    @Test
    fun `normalize maps a common POM spelling to its SPDX id`() {
        assertEquals("Apache-2.0", extractor.normalize("Apache License, Version 2.0"))
    }

    @Test
    fun `normalize is case-insensitive and trims whitespace`() {
        assertEquals("Apache-2.0", extractor.normalize("  apache license, version 2.0  "))
    }

    @Test
    fun `normalize returns null for an unrecognized spelling`() {
        assertNull(extractor.normalize("Some Totally Unknown License"))
    }

    // --- parseLicenses() ----------------------------------------------

    @Test
    fun `parseLicenses extracts name and url from a well-formed POM`() {
        val pom = pomWithLicenses("Apache License, Version 2.0" to "https://www.apache.org/licenses/LICENSE-2.0")
        val licenses = extractor.parseLicenses(pom)
        assertEquals(1, licenses.size)
        assertEquals("Apache License, Version 2.0", licenses[0].name)
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", licenses[0].url)
    }

    @Test
    fun `parseLicenses returns empty list when the pom file does not exist`() {
        val missing = File("/nonexistent/does-not-exist.pom")
        assertTrue(extractor.parseLicenses(missing).isEmpty())
    }

    @Test
    fun `parseLicenses returns empty list for a pom with no licenses block`() {
        val dir = Files.createTempDirectory("pom-license-extractor-test").toFile()
        val pom = File(dir, "no-licenses.pom")
        pom.writeText("<project><groupId>com.example</groupId></project>")
        assertTrue(extractor.parseLicenses(pom).isEmpty())
    }

    @Test
    fun `parseLicenses returns empty list for malformed xml instead of throwing`() {
        val dir = Files.createTempDirectory("pom-license-extractor-test").toFile()
        val pom = File(dir, "malformed.pom")
        pom.writeText("<project><licenses><license><name>Oops")
        assertTrue(extractor.parseLicenses(pom).isEmpty())
    }

    // --- resolve() : positive path (valid POM -> SPDX) ------------------

    @Test
    fun `resolve extracts SPDX id and url from a valid single-license POM`() {
        val pom = pomWithLicenses("Apache License, Version 2.0" to "https://www.apache.org/licenses/LICENSE-2.0")
        val resolution = extractor.resolve("com.example:lib:1.0.0", pom, overrides = emptyMap())
        assertEquals("Apache-2.0", resolution.license)
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", resolution.url)
        assertTrue(resolution.otherLicenses.isEmpty())
    }

    // --- resolve() : negative path (missing POM -> override lookup) -----

    @Test
    fun `resolve falls back to override when pom file is missing`() {
        val overrides = mapOf(
            "com.example:lib:1.0.0" to PomLicenseExtractor.Override("MIT", "https://github.com/example/lib/blob/main/LICENSE"),
        )
        val resolution = extractor.resolve("com.example:lib:1.0.0", pomFile = null, overrides = overrides)
        assertEquals("MIT", resolution.license)
        assertEquals("https://github.com/example/lib/blob/main/LICENSE", resolution.url)
    }

    @Test
    fun `resolve falls back to override when pom has no recognizable license`() {
        val pom = pomWithLicenses("Some Totally Unknown License" to "")
        val overrides = mapOf(
            "com.example:lib:1.0.0" to PomLicenseExtractor.Override("MIT", "https://example.com/verified"),
        )
        val resolution = extractor.resolve("com.example:lib:1.0.0", pom, overrides)
        assertEquals("MIT", resolution.license)
    }

    @Test
    fun `resolve returns UNKNOWN when pom is missing and there is no override`() {
        val resolution = extractor.resolve("com.example:lib:1.0.0", pomFile = null, overrides = emptyMap())
        assertEquals("UNKNOWN", resolution.license)
        assertEquals("", resolution.url)
    }

    // --- resolve() : edge case (multi-license POM -> first allowlisted) --

    @Test
    fun `resolve picks the first allowlisted license on a multi-license pom and records the other`() {
        val pom = pomWithLicenses(
            "GNU Lesser General Public License, Version 2.1" to "https://www.gnu.org/licenses/lgpl-2.1",
            "MIT License" to "https://opensource.org/licenses/MIT",
        )
        val resolution = extractor.resolve("com.example:dual-licensed:2.0.0", pom, overrides = emptyMap())
        assertEquals("MIT", resolution.license)
        assertEquals("https://opensource.org/licenses/MIT", resolution.url)
        assertEquals(listOf("LGPL-2.1"), resolution.otherLicenses)
    }

    @Test
    fun `resolve prefers the first declared allowlisted license when several are allowlisted`() {
        val pom = pomWithLicenses(
            "MIT License" to "https://opensource.org/licenses/MIT",
            "Apache License, Version 2.0" to "https://www.apache.org/licenses/LICENSE-2.0",
        )
        val resolution = extractor.resolve("com.example:dual-licensed:2.0.0", pom, overrides = emptyMap())
        assertEquals("MIT", resolution.license)
    }

    // --- companion loaders ----------------------------------------------

    @Test
    fun `loadAliases skips comment keys and reads spdx alias arrays`() {
        val dir = Files.createTempDirectory("pom-license-extractor-test").toFile()
        val file = File(dir, "spdx-aliases.json")
        file.writeText(
            """
            |{
            |  "_comment": "ignore me",
            |  "MIT": ["MIT License", "The MIT License"]
            |}
            """.trimMargin()
        )
        val loaded = PomLicenseExtractor.loadAliases(file)
        assertEquals(listOf("MIT License", "The MIT License"), loaded["MIT"])
        assertTrue(!loaded.containsKey("_comment"))
    }

    @Test
    fun `loadAllowlist ignores blank lines and comments`() {
        val dir = Files.createTempDirectory("pom-license-extractor-test").toFile()
        val file = File(dir, "allowlist.txt")
        file.writeText(
            """
            |# SPDX allowlist
            |Apache-2.0
            |
            |MIT
            """.trimMargin()
        )
        val loaded = PomLicenseExtractor.loadAllowlist(file)
        assertEquals(setOf("Apache-2.0", "MIT"), loaded)
    }

    @Test
    fun `loadOverrides reads license and source_url, skipping comment entries`() {
        val dir = Files.createTempDirectory("pom-license-extractor-test").toFile()
        val file = File(dir, "overrides.json")
        file.writeText(
            """
            |{
            |  "_comment": "ignore me",
            |  "org.example:example-lib": {
            |    "license": "MIT",
            |    "reason": "Verified from GitHub repository license file",
            |    "source_url": "https://github.com/example/example-lib/blob/main/LICENSE"
            |  }
            |}
            """.trimMargin()
        )
        val loaded = PomLicenseExtractor.loadOverrides(file)
        assertEquals(1, loaded.size)
        assertEquals("MIT", loaded["org.example:example-lib"]?.license)
        assertEquals(
            "https://github.com/example/example-lib/blob/main/LICENSE",
            loaded["org.example:example-lib"]?.url,
        )
    }
}

package app.skein.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class SyntheticFixturesTest {
    @Test
    fun gold_and_general_sources_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { SyntheticFixtures.parse(fixture(extra = ",\"gold\":{}")) }
        assertThrows(IllegalArgumentException::class.java) { SyntheticFixtures.parse(fixture(scope = "general")) }
    }

    @Test
    fun exact_unicode_revision_and_byte_range_are_checked() {
        val parsed = SyntheticFixtures.parse(fixture()).single()
        assertEquals("Café 🧶", parsed.sources.single().text)
        assertEquals(
            10,
            parsed.sources
                .single()
                .locator
                ?.byteEnd,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtures.parse(fixture().replace("\"byte_end\":10", "\"byte_end\":7"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticFixtures.parse(fixture().replace(sha256("Café 🧶".toByteArray()), "0".repeat(64)))
        }
    }

    @Test
    fun duplicate_cases_and_path_escape_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { SyntheticFixtures.parse(fixture() + "\n" + fixture()) }
        val root = Files.createTempDirectory("synthetic-fixture").toFile().canonicalFile
        val allowed = root.resolve("input").also { it.mkdir() }
        val outside = root.resolve("outside").also { it.writeText("synthetic") }
        try {
            assertThrows(IllegalArgumentException::class.java) { confinedFile(allowed, outside.path) }
            val link = allowed.resolve("linked")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { confinedFile(allowed, link.path) }
            val linkedRoot = root.resolve("linked-root")
            Files.createSymbolicLink(linkedRoot.toPath(), root.toPath())
            assertThrows(IllegalArgumentException::class.java) { confinedFile(linkedRoot, outside.path) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fixture(
        scope: String = "knowledge",
        extra: String = "",
    ): String =
        """
        {"schema_version":1,"case_id":"dev-one","scope":"$scope","query":"What?","history":[],"sources":[
        {"chunk_id":1,"doc_id":"doc","revision_hash":"${sha256("Café 🧶".toByteArray())}",
        "title":"Synthetic","text":"Café 🧶","score":1.0,"source_kind":"NOTE","byte_start":0,"byte_end":10}]$extra}
        """.trimIndent().replace("\n", "")
}

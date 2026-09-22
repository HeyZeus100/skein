package app.skein.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `E10.I3` (skein-gzr): TDD for the `contractReport` parser, written before
 * [ContractReportTask]'s implementation per the plan's Step 1 and this
 * repo's TDD convention (fixture JUnit XML in, expected table rows out,
 * including a "pending &lt;bead&gt;" row).
 *
 * Fixture XML below matches the *actual* shape Gradle's JUnit XML writer
 * produces — verified empirically against a real `@Ignore("reason")` test
 * run in `:testing` while designing this task: the `<skipped/>` element
 * carries no `message` attribute, so the `@Ignore` reason (and therefore
 * which bead a pending suite is blocked on) is NOT recoverable from the XML
 * alone. That is why [ContractReportTask.SUITES] — not the XML — is the
 * source of truth for the suite/implementation/bead mapping; the XML is
 * only ever consulted for pass/fail/skip *counts* per test class.
 */
class ContractReportTaskTest {

    // ------------------------------------------------------------------
    // Pure XML parsing.
    // ------------------------------------------------------------------

    @Test
    fun `parses a passing JUnit XML file into a passed class result`() {
        val file = fixture(
            "TEST-us.aherrera.skein.testing.FakeInferenceEngineTest.xml",
            junitXml(
                classname = "us.aherrera.skein.testing.FakeInferenceEngineTest",
                testcases = listOf(TestCaseFixture("stream_emits_done_last")),
            ),
        )

        val results = ContractReportTask.parseResults(setOf(file))

        val result = results.getValue("us.aherrera.skein.testing.FakeInferenceEngineTest")
        assertEquals(1, result.passed)
        assertEquals(0, result.failed)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `parses a failing JUnit XML file into a failed class result`() {
        val file = fixture(
            "TEST-app.skein.core.vault.repository.VaultRepositoryImplContractTest.xml",
            junitXml(
                classname = "app.skein.core.vault.repository.VaultRepositoryImplContractTest",
                testcases = listOf(
                    TestCaseFixture("ok_case"),
                    TestCaseFixture("broken_case", failed = true),
                ),
            ),
        )

        val results = ContractReportTask.parseResults(setOf(file))

        val result = results.getValue("app.skein.core.vault.repository.VaultRepositoryImplContractTest")
        assertEquals(1, result.passed)
        assertEquals(1, result.failed)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `parses an ignored JUnit XML file into a skipped class result`() {
        val file = fixture(
            "TEST-app.skein.core.inference.LlamaCppEngineTest.xml",
            junitXml(
                classname = "app.skein.core.inference.LlamaCppEngineTest",
                testcases = listOf(TestCaseFixture("stream_emits_done_last", skipped = true)),
            ),
        )

        val results = ContractReportTask.parseResults(setOf(file))

        val result = results.getValue("app.skein.core.inference.LlamaCppEngineTest")
        assertEquals(0, result.passed)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
    }

    // ------------------------------------------------------------------
    // Status derivation — [ContractReportTask.statusFor].
    // ------------------------------------------------------------------

    @Test
    fun `a registry entry with no pending bead and only passing testcases is passed`() {
        val entry = ContractReportTask.ContractEntry(
            suite = "InferenceEngine",
            implementation = "FakeInferenceEngine",
            testClassName = "us.aherrera.skein.testing.FakeInferenceEngineTest",
        )
        val results = mapOf(
            entry.testClassName to ContractReportTask.ClassResult(passed = 6, failed = 0, skipped = 0),
        )

        assertEquals("passed", ContractReportTask.statusFor(entry, results))
    }

    @Test
    fun `a registry entry with any failing testcase is failed`() {
        val entry = ContractReportTask.ContractEntry(
            suite = "VaultRepository",
            implementation = "VaultRepositoryImpl",
            testClassName = "app.skein.core.vault.repository.VaultRepositoryImplContractTest",
        )
        val results = mapOf(
            entry.testClassName to ContractReportTask.ClassResult(passed = 5, failed = 1, skipped = 0),
        )

        assertEquals("failed", ContractReportTask.statusFor(entry, results))
    }

    @Test
    fun `a pending-bead registry entry whose testcases were skipped reports pending with the bead id`() {
        val entry = ContractReportTask.ContractEntry(
            suite = "InferenceEngine",
            implementation = "LlamaCppEngine",
            testClassName = "app.skein.core.inference.LlamaCppEngineTest",
            pendingBead = "skein-1uw",
        )
        val results = mapOf(
            entry.testClassName to ContractReportTask.ClassResult(passed = 0, failed = 0, skipped = 6),
        )

        assertEquals("pending skein-1uw", ContractReportTask.statusFor(entry, results))
    }

    @Test
    fun `a registry entry with no matching XML at all is not run`() {
        val entry = ContractReportTask.ContractEntry(
            suite = "EmbedderService",
            implementation = "EmbedderServiceImpl",
            testClassName = "app.skein.core.rag.embed.EmbedderServiceImplTest",
            pendingBead = "skein-079",
        )

        assertEquals("not run", ContractReportTask.statusFor(entry, emptyMap()))
    }

    @Test
    fun `a failure always outranks a pending bead for the same class`() {
        // Guards against a future registry entry keeping its pendingBead
        // set after the real implementation starts failing on the JVM —
        // a real red test must show as failed, never masked as "pending".
        val entry = ContractReportTask.ContractEntry(
            suite = "PromptAssembler",
            implementation = "PromptAssemblerImpl",
            testClassName = "app.skein.core.rag.prompt.PromptAssemblerImplTest",
            pendingBead = "skein-82g",
        )
        val results = mapOf(
            entry.testClassName to ContractReportTask.ClassResult(passed = 10, failed = 1, skipped = 0),
        )

        assertEquals("failed", ContractReportTask.statusFor(entry, results))
    }

    // ------------------------------------------------------------------
    // Table rendering.
    // ------------------------------------------------------------------

    @Test
    fun `renders a header and one row per registry entry`() {
        val rows = listOf(
            ContractReportTask.ContractEntry("Suite", "FakeImpl", "x.FakeTest") to "passed",
            ContractReportTask.ContractEntry("Suite", "RealImpl", "x.RealTest") to "not run",
            ContractReportTask.ContractEntry("Suite", "StubImpl", "x.StubTest", pendingBead = "skein-1uw") to
                "pending skein-1uw",
        )

        val table = ContractReportTask.renderTable(rows)

        assertTrue(table.contains("Suite"))
        assertTrue(table.contains("FakeImpl"))
        assertTrue(table.contains("passed"))
        assertTrue(table.contains("RealImpl"))
        assertTrue(table.contains("not run"))
        assertTrue(table.contains("StubImpl"))
        assertTrue(table.contains("pending skein-1uw"))
    }

    // ------------------------------------------------------------------
    // End-to-end task action, via ProjectBuilder — mirrors
    // LicenseAuditTaskTest's pattern of calling the @TaskAction method
    // directly rather than running a full Gradle build.
    // ------------------------------------------------------------------

    @Test
    fun `task action writes a report file containing a pending row and a not-run row`() {
        val project = ProjectBuilder.builder().build()
        val xmlDir = project.layout.buildDirectory.dir("fixture-xml").get().asFile
        xmlDir.mkdirs()
        File(xmlDir, "TEST-app.skein.core.inference.LlamaCppEngineTest.xml").writeText(
            junitXml(
                classname = "app.skein.core.inference.LlamaCppEngineTest",
                testcases = listOf(TestCaseFixture("stream_emits_done_last", skipped = true)),
            ),
        )

        val task = project.tasks.register("contractReportTest", ContractReportTask::class.java).get()
        task.junitXmlFiles.setFrom(project.fileTree(xmlDir) { include("**/*.xml") })
        task.reportFile.set(project.layout.buildDirectory.file("reports/contract/test.txt"))

        task.report()

        val text = task.reportFile.get().asFile.readText()
        assertTrue(
            "expected the LlamaCppEngine row to read 'pending skein-1uw':\n$text",
            text.contains("LlamaCppEngine") && text.contains("pending skein-1uw"),
        )
        assertTrue(
            "expected at least one 'not run' row for a registry entry with no matching XML:\n$text",
            text.contains("not run"),
        )
        assertFalse(
            "a class with only skipped testcases must never be reported as passed",
            Regex("LlamaCppEngine\\s+.*\\bpassed\\b").containsMatchIn(text),
        )
    }

    private fun fixture(
        name: String,
        content: String,
    ): File {
        val dir = createTempDir(prefix = "contract-report-test")
        val file = File(dir, name)
        file.writeText(content)
        return file
    }

    private data class TestCaseFixture(
        val name: String,
        val failed: Boolean = false,
        val skipped: Boolean = false,
    )

    /** Mirrors the real shape captured from a `./gradlew :testing:test` run (no `message` on `<skipped/>`). */
    private fun junitXml(
        classname: String,
        testcases: List<TestCaseFixture>,
    ): String {
        val body = testcases.joinToString("\n") { tc ->
            when {
                tc.failed ->
                    """  <testcase name="${tc.name}" classname="$classname" time="0.0">
                      |    <failure message="boom">stack trace</failure>
                      |  </testcase>
                    """.trimMargin()
                tc.skipped ->
                    """  <testcase name="${tc.name}" classname="$classname" time="0.0">
                      |    <skipped/>
                      |  </testcase>
                    """.trimMargin()
                else -> """  <testcase name="${tc.name}" classname="$classname" time="0.0"/>"""
            }
        }
        val skippedCount = testcases.count { it.skipped }
        val failedCount = testcases.count { it.failed }
        return """<?xml version="1.0" encoding="UTF-8"?>
            |<testsuite name="$classname" tests="${testcases.size}" skipped="$skippedCount" failures="$failedCount" errors="0" timestamp="2026-09-21T00:00:00Z" hostname="test" time="0.0">
            |  <properties/>
            |$body
            |  <system-out><![CDATA[]]></system-out>
            |  <system-err><![CDATA[]]></system-err>
            |</testsuite>
        """.trimMargin()
    }
}

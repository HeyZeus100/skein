package app.skein.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `E10.I3` (skein-gzr): prints (and writes) a suite × implementation ×
 * status table for every plan §4 contract suite in `testing/src/main`,
 * covering the fake-backed JVM subclass, the real-implementation
 * subclass(es), and (until the implementing bead lands) an `@Ignore`d
 * placeholder subclass.
 *
 * ## Why a static registry, not just JUnit XML
 *
 * [SUITES] is the source of truth for *which* bead a suite/implementation
 * pair is pending on. This was a deliberate choice, verified empirically
 * while building this task: Gradle's default JUnit XML writer records an
 * `@Ignore("reason")` test as a bare `<skipped/>` element with **no**
 * `message` attribute, so the ignore reason (and therefore the bead id) is
 * not recoverable by parsing XML alone. The XML is still exactly what
 * [parseResults] parses — it is the source of truth for whether a given
 * class's tests actually ran and what they did (passed / failed / were
 * skipped); [SUITES] just supplies the piece XML cannot: which bead a
 * skipped placeholder is blocked on.
 *
 * ## Statuses
 *
 * - `passed` — every discovered testcase for that class passed, none skipped/failed.
 * - `failed` — at least one testcase failed or errored (always wins over a stale `pendingBead`).
 * - `pending <bead>` — no failures, at least one testcase skipped, and the registry entry names a bead.
 * - `not run` — no JUnit XML was found for that class at all (e.g. an `androidTest`-only
 *   suite in this environment, which never runs without a device — see docs/TESTING.md).
 */
abstract class ContractReportTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val junitXmlFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun report() {
        val results = parseResults(junitXmlFiles.files)
        val rows = SUITES.map { entry -> entry to statusFor(entry, results) }
        val table = renderTable(rows)

        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(table)

        logger.lifecycle(table)
    }

    /** One suite/implementation pairing this task knows how to report on. */
    data class ContractEntry(
        val suite: String,
        val implementation: String,
        val testClassName: String,
        val pendingBead: String? = null,
    )

    /** Aggregate JUnit outcome for one test class, across every XML file that reported it. */
    data class ClassResult(
        val passed: Int,
        val failed: Int,
        val skipped: Int,
    )

    companion object {

        /**
         * Every abstract contract suite in `testing/src/main/kotlin/us/aherrera/skein/testing/`
         * (as of `E10.I3`), crossed with every known subclass:
         *  - the fake-backed JVM subclass in `testing/src/test` (all nine suites have one — `E10.I2`/skein-0j1),
         *  - the real-implementation subclass where one exists (`E2.I4`, `E2.I15`, `E2.I14`, `E2.I13`, `E5.I13`),
         *  - a `GuardedReferenceAssembler` double proving `PromptGuard`'s placement (`E3.I10`) ahead of
         *    the real `PromptAssemblerImpl` (`E5.I15`), and
         *  - the `E10.I3` `@Ignore`d placeholders for the three implementations that do not exist yet:
         *    `LlamaCppEngine` (`E4.I4` / skein-1uw), `EmbedderServiceImpl` (`E5.I3` / skein-079), and
         *    `PromptAssemblerImpl` (`E5.I15` / skein-82g).
         *
         * `ExportServiceImpl` (`E2.I10`) has no contract subclass (real or stub) yet — it is out of
         * `E10.I3`'s scope (see the bead's acceptance criteria); a follow-up bead tracks adding one.
         *
         * Update this list alongside any change to which contract subclasses exist — it is not derived
         * automatically from the source tree.
         */
        val SUITES: List<ContractEntry> = listOf(
            ContractEntry(
                suite = "InferenceEngine",
                implementation = "FakeInferenceEngine",
                testClassName = "us.aherrera.skein.testing.FakeInferenceEngineTest",
            ),
            ContractEntry(
                suite = "InferenceEngine",
                implementation = "LlamaCppEngine",
                testClassName = "app.skein.core.inference.LlamaCppEngineTest",
                pendingBead = "skein-1uw",
            ),
            ContractEntry(
                suite = "VaultRepository",
                implementation = "InMemoryVaultRepository",
                testClassName = "us.aherrera.skein.testing.InMemoryVaultRepositoryTest",
            ),
            ContractEntry(
                suite = "VaultRepository",
                implementation = "VaultRepositoryImpl",
                testClassName = "app.skein.core.vault.repository.VaultRepositoryImplContractTest",
            ),
            ContractEntry(
                suite = "IndexStore",
                implementation = "InMemoryIndexStore",
                testClassName = "us.aherrera.skein.testing.InMemoryIndexStoreTest",
            ),
            ContractEntry(
                suite = "IndexStore",
                implementation = "IndexStoreImpl",
                testClassName = "app.skein.core.vault.index.IndexStoreImplContractTest",
            ),
            ContractEntry(
                suite = "RetrievalService",
                implementation = "FakeRetrievalService",
                testClassName = "us.aherrera.skein.testing.FakeRetrievalServiceTest",
            ),
            ContractEntry(
                suite = "RetrievalService",
                implementation = "RetrievalServiceImpl",
                testClassName = "app.skein.core.rag.retrieval.RetrievalServiceImplContractTest",
            ),
            ContractEntry(
                suite = "PromptAssembler",
                implementation = "FakePromptAssembler",
                testClassName = "us.aherrera.skein.testing.FakePromptAssemblerTest",
            ),
            ContractEntry(
                suite = "PromptAssembler",
                implementation = "GuardedReferenceAssembler",
                testClassName = "us.aherrera.skein.security.prompt.GuardedPromptAssemblerContractTest",
            ),
            ContractEntry(
                suite = "PromptAssembler",
                implementation = "PromptAssemblerImpl",
                testClassName = "app.skein.core.rag.prompt.PromptAssemblerImplTest",
                pendingBead = "skein-82g",
            ),
            ContractEntry(
                suite = "PersonaService",
                implementation = "InMemoryPersonaService",
                testClassName = "us.aherrera.skein.testing.InMemoryPersonaServiceTest",
            ),
            ContractEntry(
                suite = "PersonaService",
                implementation = "PersonaServiceImpl",
                testClassName = "app.skein.core.vault.persona.PersonaServiceImplContractTest",
            ),
            ContractEntry(
                suite = "EmbedderService",
                implementation = "FakeEmbedderService",
                testClassName = "us.aherrera.skein.testing.FakeEmbedderServiceTest",
            ),
            ContractEntry(
                suite = "EmbedderService",
                implementation = "EmbedderServiceImpl",
                testClassName = "app.skein.core.rag.embed.EmbedderServiceImplTest",
                pendingBead = "skein-079",
            ),
            ContractEntry(
                suite = "ImportService",
                implementation = "FakeImportService",
                testClassName = "us.aherrera.skein.testing.FakeImportServiceTest",
            ),
            ContractEntry(
                suite = "ImportService",
                implementation = "ImportServiceImpl",
                testClassName = "app.skein.core.vault.transfer.ImportServiceImplContractTest",
            ),
            ContractEntry(
                suite = "ExportService",
                implementation = "FakeExportService",
                testClassName = "us.aherrera.skein.testing.FakeExportServiceTest",
            ),
        )

        /**
         * Derives the reported status for [entry] from its aggregate [ClassResult] (or its absence)
         * in [results]. A failure always wins over a `pendingBead` so a placeholder that starts
         * failing after its `@Ignore` is removed is never masked as still "pending".
         */
        fun statusFor(
            entry: ContractEntry,
            results: Map<String, ClassResult>,
        ): String {
            val result = results[entry.testClassName] ?: return "not run"
            return when {
                result.failed > 0 -> "failed"
                entry.pendingBead != null && result.skipped > 0 -> "pending ${entry.pendingBead}"
                result.passed > 0 -> "passed"
                else -> "not run"
            }
        }

        /**
         * Parses every JUnit XML file in [files] (Gradle's Ant-style `<testsuite>` report, the same
         * format every `test`/`test<Variant>UnitTest` task already writes under its module's
         * `build/test-results` directory tree), aggregating pass/fail/skip counts per `classname`.
         * A `classname` that appears in more than one file (e.g. re-run) has its counts summed.
         */
        fun parseResults(files: Set<File>): Map<String, ClassResult> {
            val builderFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val totals = mutableMapOf<String, IntArray>() // [passed, failed, skipped]

            for (file in files) {
                if (!file.exists() || !file.name.endsWith(".xml")) continue
                val document = builderFactory.newDocumentBuilder().parse(file)
                val testcases = document.getElementsByTagName("testcase")
                for (i in 0 until testcases.length) {
                    val testcase = testcases.item(i) as Element
                    val classname = testcase.getAttribute("classname")
                    if (classname.isEmpty()) continue
                    val counts = totals.getOrPut(classname) { IntArray(3) }

                    val failed = testcase.getElementsByTagName("failure").length > 0 ||
                        testcase.getElementsByTagName("error").length > 0
                    val skipped = testcase.getElementsByTagName("skipped").length > 0

                    when {
                        failed -> counts[1] += 1
                        skipped -> counts[2] += 1
                        else -> counts[0] += 1
                    }
                }
            }

            return totals.mapValues { (_, counts) -> ClassResult(passed = counts[0], failed = counts[1], skipped = counts[2]) }
        }

        /** Renders [rows] (each a [ContractEntry] and its derived status) as a fixed-width text table. */
        fun renderTable(rows: List<Pair<ContractEntry, String>>): String {
            val headers = listOf("Suite", "Implementation", "Status")
            val columns = rows.map { (entry, status) -> listOf(entry.suite, entry.implementation, status) }
            val widths = headers.indices.map { col ->
                (listOf(headers[col]) + columns.map { it[col] }).maxOf { it.length }
            }

            fun renderRow(cells: List<String>): String =
                cells.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()

            val separator = widths.joinToString("  ") { "-".repeat(it) }

            return buildString {
                appendLine(renderRow(headers))
                appendLine(separator)
                columns.forEach { appendLine(renderRow(it)) }
            }
        }
    }
}

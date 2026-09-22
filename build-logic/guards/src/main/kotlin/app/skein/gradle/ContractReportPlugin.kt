package app.skein.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers the root-level `contractReport` task (`E10.I3` / skein-gzr):
 * one aggregate report across every subproject's JUnit XML, not a
 * per-module task, so it is applied to the *root* project only (`build.gradle.kts`),
 * not per-subproject the way the manifest/dependency/isolation guards are.
 *
 * `contractReport` does not itself run any tests — it reports on whatever
 * JUnit XML already exists under each subproject's `build/test-results`
 * directory tree.
 * Run `./gradlew test contractReport` (or let it ride behind `check`) for a
 * fresh report; running `contractReport` alone after a stale/clean build
 * will show every suite as "not run".
 *
 * Deliberately **not** wired into `check` or any CI workflow here — the
 * coordinator's `E10.I3` note reserves that wiring for a follow-up bead
 * once `skein-ddp` (the `.github/workflows` owner during this session)
 * lands, to avoid a concurrent edit to files this bead's scope excludes.
 */
class ContractReportPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "app.skein.contractreport must be applied to the root project only, was applied to ${project.path}"
        }

        val xmlFiles = project.objects.fileCollection()
        project.rootProject.subprojects.forEach { subproject ->
            xmlFiles.from(
                project.fileTree(subproject.layout.buildDirectory.dir("test-results")) {
                    include("**/*.xml")
                },
            )
        }

        project.tasks.register("contractReport", ContractReportTask::class.java) {
            group = "verification"
            description = "Prints a suite x implementation x status table for the plan's §4 contract " +
                "test suites (E10.I3), parsed from every module's JUnit XML."
            junitXmlFiles.setFrom(xmlFiles)
            reportFile.set(project.layout.buildDirectory.file("reports/contract/contractReport.txt"))
        }
    }
}

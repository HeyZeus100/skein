package app.skein.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseAuditTaskTest {

    @Test
    fun `passes when all licenses are in allowlist`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("licenseAuditTest", LicenseAuditTask::class.java).get()
        task.variant.set("fossRelease")
        task.artifactLicenses.set(mapOf(
            "androidx.core:core:1.10.0" to "Apache-2.0",
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.0" to "Apache-2.0"
        ))
        task.reportFile.set(project.layout.buildDirectory.file("reports/test.md"))
        task.outputDir.set(project.layout.buildDirectory.dir("test-licenses"))

        // Should not throw
        task.auditLicenses()
    }

    @Test
    fun `fails when foss variant contains non-allowlist license`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("licenseAuditTest", LicenseAuditTask::class.java).get()
        task.variant.set("fossRelease")
        task.artifactLicenses.set(mapOf(
            "androidx.core:core:1.10.0" to "Apache-2.0",
            "org.hibernate:hibernate-core:5.4.0" to "LGPL-2.1"
        ))
        task.reportFile.set(project.layout.buildDirectory.file("reports/test.md"))
        task.outputDir.set(project.layout.buildDirectory.dir("test-licenses"))

        val error = runCatching { task.auditLicenses() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for LGPL-2.1 license")
        assertTrue(error.message!!.contains("LGPL-2.1"))
        assertTrue(error.message!!.contains("not in the allowlist"))
    }

    @Test
    fun `respects override file for ambiguous licenses`() {
        val project = ProjectBuilder.builder().build()

        // Create a temporary override file
        val overridesFile = project.layout.buildDirectory.dir("temp").get().asFile
        overridesFile.mkdirs()
        val overridesJson = File(overridesFile, "overrides.json")
        overridesJson.writeText(
            """{
  "com.example:lib": {
    "license": "MIT",
    "reason": "Verified from source",
    "source_url": "https://github.com/example/lib"
  }
}"""
        )

        val task = project.tasks.register("licenseAuditTest", LicenseAuditTask::class.java).get()
        task.variant.set("fossRelease")
        task.artifactLicenses.set(mapOf(
            "com.example:lib:1.0.0" to "UNKNOWN"
        ))
        task.overridesFile.set(overridesJson)
        task.reportFile.set(project.layout.buildDirectory.file("reports/test.md"))
        task.outputDir.set(project.layout.buildDirectory.dir("test-licenses"))

        // Should not throw because override provides MIT license
        task.auditLicenses()
    }

    @Test
    fun `generates deterministic licenses json`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("licenseAuditTest", LicenseAuditTask::class.java).get()
        task.variant.set("fossRelease")
        task.artifactLicenses.set(mapOf(
            "androidx.core:core:1.10.0" to "Apache-2.0",
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.0" to "Apache-2.0"
        ))
        task.reportFile.set(project.layout.buildDirectory.file("reports/test.md"))
        task.outputDir.set(project.layout.buildDirectory.dir("test-licenses"))

        task.auditLicenses()
        val licensesJsonFile = File(task.outputDir.get().asFile, "licenses.json")
        val firstRunText = licensesJsonFile.readText()

        task.auditLicenses()
        val secondRunText = licensesJsonFile.readText()

        assertTrue("licenses.json should be deterministic", firstRunText == secondRunText)
    }

    @Test
    fun `resolves licenses json to a build-generated assets directory, not src main assets`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("licenseAuditTest", LicenseAuditTask::class.java).get()
        task.variant.set("fossRelease")
        task.artifactLicenses.set(mapOf(
            "androidx.core:core:1.10.0" to "Apache-2.0"
        ))
        task.reportFile.set(project.layout.buildDirectory.file("reports/test.md"))

        // skein-iau5: the output must live under build/generated/licenses/,
        // registered as a generated asset source via AGP's Variant API
        // (LicenseAuditPlugin), and must never be written into
        // src/main/assets — writing directly into that source-tree
        // directory hid the producer relationship from Gradle and tripped
        // the "implicit dependency" task-graph validation added in Gradle
        // 9.7.1, failing `./gradlew check`.
        val outputDir = project.layout.buildDirectory.dir("generated/licenses/main/assets")
        task.outputDir.set(outputDir)

        task.auditLicenses()
        val outputFile = File(task.outputDir.get().asFile, "licenses.json")

        assertTrue(
            "licenses.json should be under build/generated/licenses/",
            outputFile.path.contains("build/generated/licenses")
        )
        assertTrue(
            "licenses.json must not be written into src/main/assets",
            !outputFile.path.contains("src/main/assets")
        )
        assertTrue("Output file should exist", outputFile.exists())
    }
}

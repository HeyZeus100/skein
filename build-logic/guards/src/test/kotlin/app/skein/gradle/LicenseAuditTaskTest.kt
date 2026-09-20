package app.skein.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

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
        task.licensesJsonFile.set(project.layout.buildDirectory.file("test-licenses.json"))

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
        task.licensesJsonFile.set(project.layout.buildDirectory.file("test-licenses.json"))

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
        val overridesJson = java.io.File(overridesFile, "overrides.json")
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
        task.licensesJsonFile.set(project.layout.buildDirectory.file("test-licenses.json"))

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
        task.licensesJsonFile.set(project.layout.buildDirectory.file("test-licenses.json"))

        task.auditLicenses()
        val firstRunText = task.licensesJsonFile.get().asFile.readText()

        task.auditLicenses()
        val secondRunText = task.licensesJsonFile.get().asFile.readText()

        assertTrue("licenses.json should be deterministic", firstRunText == secondRunText)
    }
}

// E1.I2 — manifest / dependency / module-isolation guards.
//
// This is a plain Gradle plugin module (kotlin-dsl), built and included via
// `includeBuild("build-logic")` in the root settings.gradle.kts. It is
// intentionally its own build so the guard logic can depend on the Android
// Gradle Plugin API (for the merged-manifest artifact) without adding that
// dependency to the main app's build classpath.
plugins {
    `kotlin-dsl`
}

group = "app.skein.buildlogic"

dependencies {
    // Needed for the Variant API (AndroidComponentsExtension / SingleArtifact)
    // used by ManifestGuardPlugin to locate each variant's merged manifest.
    compileOnly("com.android.tools.build:gradle:9.4.0")

    // Used by LicenseAuditTask for JSON parsing of overrides
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    testImplementation("com.android.tools.build:gradle:9.4.0")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("manifestGuard") {
            id = "app.skein.guard.manifest"
            implementationClass = "app.skein.gradle.ManifestGuardPlugin"
        }
        register("dependencyGuard") {
            id = "app.skein.guard.dependency"
            implementationClass = "app.skein.gradle.DependencyGuardPlugin"
        }
        register("isolationGuard") {
            id = "app.skein.guard.isolation"
            implementationClass = "app.skein.gradle.IsolationGuardPlugin"
        }
        register("licenseAudit") {
            id = "app.skein.guard.license"
            implementationClass = "app.skein.gradle.LicenseAuditPlugin"
        }
        register("noRawLogging") {
            id = "app.skein.guard.logging"
            implementationClass = "app.skein.gradle.NoRawLoggingGuardPlugin"
        }
    }
}

tasks.test {
    useJUnit()
}

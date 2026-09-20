// Included build (composite build) hosting Skein's Gradle guards.
// See E1.I2: manifest / dependency / module-isolation guards that fail the
// build if the app's non-negotiables (no INTERNET, no GMS, isolated
// services) regress.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "build-logic"

include(":guards")

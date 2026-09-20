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

rootProject.name = "skein"

include(
    ":app",
    ":core:model",
    ":core:ipc",
    ":core:vault",
    ":core:security",
    ":core:inference",
    ":core:rag",
    ":core:markdown",
    ":core:export",
    ":inference-service",
    ":embedder-service",
    ":feature:shell",
    ":feature:timeline",
    ":feature:chat",
    ":feature:editor",
    ":feature:graph",
    ":feature:personas",
    ":feature:settings",
    ":feature:onboarding",
    ":feature:models",
    ":testing",
)

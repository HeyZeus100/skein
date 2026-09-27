// skein-xtov.24.5 (AL-06, docs/ux/ADAPTIVE_LAYOUT_SPEC.md §8.1–8.3, §8.9):
// the typed navigation keys, `SkeinNavigationState` with its total codec, and
// the `Navigator` rules. Pure Kotlin/JVM with no dependencies beyond the
// stdlib, so features can depend on the keys without depending on one another
// (§8.1) and every rule is a plain unit test.
//
// Why no Navigation 3 or kotlinx-serialization here: Nav3's `NavKey` is an empty
// marker that only `rememberNavBackStack`/`NavKeySerializer` need, and §8.9
// forbids both (they save Java class names and throw on an unknown one). Its
// `rememberDecoratedNavEntries<T>`/`NavDisplay` take any `T`. The codec is
// written by hand so what reaches the saved-state Bundle is an explicit,
// auditable allowlist (SECURITY_REVIEW_D7.md M1–M4).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Pure Kotlin/JVM, and no project dependencies at all: never a feature
    // module or the vault (checkIsolationGuards, IsolationGuardPlugin).
    id("app.skein.guard.isolation")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

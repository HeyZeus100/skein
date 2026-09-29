# Geometry transport: pinned-source review

Recommendation: add only `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to the disposable CI helper's connected test Gradle invocation. Keep the current five geometry observations and private JSONL writer unchanged. Keep the post-Gradle `run-as app.skein cat files/foldable-gate-metrics.jsonl` collection and strict existing reviewer. This retains available observations when either test fails after writing partial evidence; it cannot manufacture observations when execution fails before Activity launch.

Pinned source chain (line numbers refer to the exact extracted source files):

1. AGP 9.4.1 `agp-sources/com/android/build/gradle/options/BooleanOption.kt:87-93`: `ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN` has exact project property `android.injected.androidTest.leaveApksInstalledAfterRun`, default false, `ApiStage.Stable`.
2. AGP `agp-sources/com/android/build/gradle/internal/testing/AndroidTestEngineConfigurer.kt:44`: release AGP selects Android test engine 1.0.1. Lines 98-101 write the inverse option value to engine property `android-test.uninstall-after-tests`.
3. Engine 1.0.1 `engine-sources/com/android/tools/androidtest/testengine/config/AndroidTestConfiguration.kt:93`: parses that property to `uninstallApksAfterTests`, default true.
4. Engine `engine-sources/com/android/tools/androidtest/testengine/descriptor/AndroidDeviceDescriptor.kt:293-302`: passes actual tested APKs, test APKs and this flag to `AndroidTestRunner`.
5. Engine `engine-sources/com/android/tools/androidtest/testengine/adb/AndroidTestRunner.kt:103-118`: `finally` runs after instrumentation even on failure. Both tested and test APK uninstall calls, plus test utility uninstall calls, occur only inside `if (uninstallApksAfterTests)`.
6. Engine `engine-sources/com/android/tools/androidtest/testengine/adb/AdbApkInstaller.kt:237-244`: remaining installer cleanup clears the debug-app setting; it does not clear package data.

The property is a Gradle project property (`-P...`), not an instrumentation-runner argument. Scope it to the disposable fold CI command; this is not authorization to leave/install/change anything on physical devices. A single fresh emulator run is assumed by the existing lane; if reusing the same emulator, the append-only private file may retain earlier rows and the existing duplicate-step reviewer must continue to reject them. No truncation or synthetic row fallback is proposed.

Official pinned source archives downloaded over normal verified HTTPS:

- https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1-sources.jar — 4,032,753 bytes, SHA256 `08a69084ca47b2681671a611113ed919b986facb79dc24404fb7348d2c62a386`.
- https://dl.google.com/dl/android/maven2/com/android/tools/androidtest/android-test-engine/1.0.1/android-test-engine-1.0.1-sources.jar — 75,808 bytes, SHA256 `0904c5f9050be0f58bcfabc4001f26ee296ce365ca7bb84dc7d93aeaf23a218f`.

Source review originals: `/Users/andrewherrera/skein-worktrees/ux-rb-evidence-20260929/build/agent-logs/foldable-geometry-transport-20260929`.

These are independently measured download hashes, not a claim of cryptographic correspondence between a source archive and a compiled binary. The project's catalog pins AGP 9.4.1 and its verified dependency graph contains engine 1.0.1. No build, device action, test execution or source edit was performed for this review. Additional-output alternatives were not pursued after the coordinator selected this minimal supported property.

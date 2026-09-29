# Framework device-state transport check

Measured source `2e1dcbafd645d4bb9348340905949bc5cae90acf` integrates reviewed worker commit `54653fb903be6a01338bb22f21761cd884833e83` followed only by required Kotlin formatting. Production inference and installed candidate remain unchanged.

The first exact check failed Kotlin formatting and is preserved as `fold-transport-check.log.gz`. The corrected `fold-transport-check2.log.gz` records successful explicit `:app:ktlintCheck`, `:app:compileDevDebugAndroidTestKotlin`, and `:app:compileFossDebugAndroidTestKotlin` with `-Pskein.foldableTests=true --max-workers=2`; 321 tasks, seven executed. The root independently ran all ten fold workflow/reviewer host tests and shell syntax checks successfully.

Both builds used the atomic shared lease, repository JDK17/SDK and pinned submodules. The repair replaces target-process TCP gRPC with guarded UiAutomation framework device-state override and rotation. It preserves the five geometry observations, same-Activity assertions, non-emulator refusal and cleanup checks. This is not hinge-sensor actuation. Fresh generic run [36559029738](https://github.com/HeyZeus100/skein/actions/runs/36559029738) was dispatched at the exact source; runtime result remains pending in this checkpoint.

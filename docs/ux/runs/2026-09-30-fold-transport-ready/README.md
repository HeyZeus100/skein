# Fold transport readiness candidate, 30 September 2026

This is bounded implementation evidence for `skein-12pt.1`, based on published `8f084343cf2b67395d2d707b33f6ee578d801c89`. It does not close emulator or physical acceptance. Source hashes and actual host results are in [source-and-results.json](source-and-results.json).

The bridge now closes inherited stdin on every no-input ADB process and uses explicit `shell -n -T` for private request reads. It records each command start, finish or timeout with its exact monotonic timestamp, target, phase and bounded deadline. A process-level fake-ADB experiment retains an open parent stdin pipe: original source times out; corrected source receives EOF. This reproduces the stdin hazard, **not the cause of original run36708565565**. Its actual XML remains two failures; original artifacts and thresholds are unchanged.

Before launching MainActivity or changing posture, instrumentation writes one run/nonce-bound readiness request at sequence0 and consumes its exact ACK. Only then does it write its private consumer receipt. The host refuses posture before readiness. Evidence review requires that receipt and the separate command transcript. Readiness gives no posture or geometry credit: all original six posture requests remain sequences1–6 and all five Activity geometry samples and both original test identities remain required. APK assembly occurs before controller polling; AGP still owns installation and XML-producing instrumentation execution.

All44 focused host tests pass, and shell syntax/whitespace checks pass. The first host attempt's single fixture-offset failure is retained alongside its corrected results. Kotlin lint/compilation and real emulator execution are pending root's serialized queue.

The appointed sole physical runner acquired its own atomic lease, ran one read-only ADB inventory, found the target absent, and released its own lease. No shell, UI, authentication, install or mutation command was sent to the physical Fold. Owner unlock remains unverified, and installed source remains historical verifiede62. Physical acceptance stays held.

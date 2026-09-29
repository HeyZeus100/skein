# Instrumentation evidence collection repair

Claimed issue `skein-op8v`. Worker `d72e1448bb669fd7784c051f0f8514538c00e4b9`, integrated as `d2e80590d`.

A scheduled successful run retained three historical XML files from committed documentation alongside its three current module XML files. The acceptance verifier already anchors current roots; its 281 actual passes were valid. This repair restricts instrumentation XML/APK inventory and workflow upload roots to `app`, `core/vault` and `inference-service`, retaining current nested flavor outputs, reports, logcat and root review metadata. Runtime verifier assertions remain byte-identical. Original current and historical evidence is preserved.

New fixtures fail against the original collector: six tests, three failures. Fixed focused tests: six passed. All CI host tests: twenty passed, zero skips, independently repeated on combined root source. Pinned submodules are verified; exactly three source files change. No app, inference, native, token/model pins, acceptance thresholds or physical-device actions change.

Raw logs are losslessly gzipped; `COPY_PROVENANCE.json` binds each copy to its unchanged original. Fresh exact-source ordinary artifact review remains pending; this source checkpoint does not close the issue.

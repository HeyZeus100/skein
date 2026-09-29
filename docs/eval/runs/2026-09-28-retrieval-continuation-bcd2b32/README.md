# Failed ordinary runtime checkpoint, source bcd2b32

Exact application source: `bcd2b3264805b9559d01622af1d17abe3ae6fc9a`.
The retained original XML and job conclusions establish a **failed** ordinary
runtime checkpoint, despite successful local compilation and automatic jobs.
No dedicated retrieval run, new validation measurement, physical-device access
or generation on the Fold occurred at this checkpoint.

[Ordinary run 36527953704](https://github.com/HeyZeus100/skein/actions/runs/36527953704)
failed. Its actual XML has 277 executed cases: app 37/37 pass, inference-service
36/36 pass, and vault 203/204 pass. There is one failure, zero errors/skips, and
no opt-in retrieval diagnostic testcase. The failure is
`IndexStoreImplAcceptanceTest.unicodeTextBindingPreservesSupplementaryAndLegacyModifiedUtf8`,
`expected to be true` at source line 327, inside the legacy-CESU8 title lookup
loop. The preceding BOM and revision assertions completed. This result must not
be changed to a pass based on host-only evidence or the other six new cases.

`ordinary/` retains all three original XML files, uploaded instrumentation review,
a separate host review, original job JSON and full log, and collection metadata.
`full-download-file-index.json` records paths, sizes and SHA-256 hashes for all
351 files in the complete downloaded artifact, preserved separately in the
runner's isolated worktree. No raw result has been rewritten.

At this same source, actual automatic job conclusions were:

- [CI 36527924795](https://github.com/HeyZeus100/skein/actions/runs/36527924795):
  lint, unit, guards and Foss assembly job passed.
- [Screenshots 36527924801](https://github.com/HeyZeus100/skein/actions/runs/36527924801):
  actual screenshot job and all eight verifyRoborazzi tasks passed.
- [Reproducibility 36527924813](https://github.com/HeyZeus100/skein/actions/runs/36527924813):
  toolchain/self-tests, both release builds, their comparison and native cold-build
  equality passed. The tag-only SQLCipher source-verification job was skipped.

Their original job JSON/logs are in `automatic-workflows/`. The coordinator's
unchanged local JVM XML summary is in `local/`; its known skipped contracts remain
explicit and do not count as passing cases. Those local and automatic results do
not override the actual ordinary runtime failure. Any repaired runtime must use
a separate source-specific bundle. Original development/reserved fixtures and
all prior failed evidence remain unchanged.

# Reproducibility evidence repair

Measured CI/helper source: `5a3ab9b986950651733e6040b43c7958f0ae25ac`.
Run [36548576075](https://github.com/HeyZeus100/skein/actions/runs/36548576075)
retains actual native libraries on success, and caps both Gradle paths at two workers.
See REVIEW.md, native-audit-2.json, combined-audit.json and final-run-review.json.

Both actual cold libraries hash to `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`;
both complete release APKs hash to `cf7261f383637ff17149b54be987cf35012aa734e1374f88c2da012cc6116163`.
The compare job and independent local comparison verify all 826 entries and whole-file equality.
All GitHub artifact digests and nested manifests verify. Tag-only SQLCipher source
regeneration is skipped. This manual topic run is not three consecutive main commits.

Large ZIP/APK/ELF originals remain at paths and hashes in
large-artifacts-retained-locally.json. Original nested SHA inventories and
REVIEW-SHA256SUMS.txt describe those owning-tree files, including intentionally
omitted binaries and uncompressed JSON/logs. The outer SHA256SUMS alone describes
this portable subset. `.gz` copies are lossless. The first audit parser's failure
is retained; native-audit-2.json corrects its parsing, without replacing build evidence.

These results do not approve the rejected `4f1b7d334` application candidate;
ordinary Android JNI default-callback failures require a corrected source and rerun.

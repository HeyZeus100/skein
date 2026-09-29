# Graph, FTS and lane repair evidence at 5cdd2f1

Source revision: `5cdd2f13785b8edf0679228e5fa5c0e6f83c98c8`. This release has
raw relevance diagnostics but no calibrated rejection policy. Fold remained HOLD;
no physical device was accessed. Original baseline evidence is unchanged.

## Ordinary instrumentation

[Run 36510513750](https://github.com/HeyZeus100/skein/actions/runs/36510513750)
passed its actual XML review. The unmodified XML contains **270 passing cases**:
app 37, vault 197, inference-service 36. There are zero failures, errors or skips,
and the opt-in retrieval class is absent. All five new row-specific FTS contracts
and the longer-title graph discovery contract passed; `targeted-cases.json`
identifies them exactly. These results establish this source's ordinary lane,
not acceptance of later rejection or post-budget changes.

The first host download of the complete artifact timed out. Its failure log and
host collection summary are retained. A separate read of the same immutable
artifact's ZIP entries obtained all three complete XML files with ZIP CRC checks;
the independent reviewer then required every module and inspected each testcase.
No instrumentation was rerun to recover these files. Full workflow logs remain
in the runner worktree under `build/agent-logs/ordinary-36510513750/`.

## Failed preliminary retrieval collection

[Run 36510517798](https://github.com/HeyZeus100/skein/actions/runs/36510517798)
**failed**. Its real XML contains one executed passing test, with zero failures,
errors or skips, and Gradle exited zero. Host collection did not pass.

The original `retrieval-failed/retrieval.json` is intentionally invalid JSON:
it ends after 30,208 bytes at byte offset 30,208 / line 588. Its SHA-256 is
`5209b6abd4a9564a251743deb35a7e95cd3e9686e3575f63711ef16a33a2067b`.
The runner recorded `report_collected=true` based only on the adb exit code, then
failed installed-APK verification with `CalledProcessError`. No installed digest
was obtained, so this is **not evidence of an actual digest mismatch**. The old
runner retained only the exception class, not that command's stderr.

The unmodified logcat shows adbd host-17 reporting a write failure and going
offline at 02:07:27.784, followed by host-18 reconnecting at 02:07:28.388. The
raw report, summary, XML, Gradle log and logcat are retained without repair or
reconstruction. Separate CRC-checked ZIP reads agree with the successful full
artifact extraction on the raw report and XML. A later fallback archive download
also timed out; its incomplete ZIP remains in the runner worktree and is not
presented as a verified archive.

All ranking, relevance, absence-rejection and FTS-audit measurements from this
attempt are **unavailable**. The partial report cannot calibrate a threshold or
satisfy an acceptance gate. Full hybrid remains **INELIGIBLE**. Collection
hardening and a coordinated new run must provide separate evidence.

## Automatic workflows

The retained workflow metadata identifies the same exact source. CI
[36510496757](https://github.com/HeyZeus100/skein/actions/runs/36510496757)
passed its unit/lint/assembly job. The actual screenshot job in
[36510496852](https://github.com/HeyZeus100/skein/actions/runs/36510496852)
passed. Reproducibility
[36510496866](https://github.com/HeyZeus100/skein/actions/runs/36510496866)
passed both builds, comparison, native determinism and toolchain tests; its
conditional SQLCipher source-verification job was skipped. These statements are
job metadata, separate from the independently inspected ordinary XML above.

`SHA256SUMS` covers every retained file except itself. Raw artifact whitespace,
including trailing blanks, is preserved. Verify from this directory with
`shasum -a 256 -c SHA256SUMS`.

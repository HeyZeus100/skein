# Blank multi-page PDF regression

The owner-requested [MarkItDown/Marker source comparison](../../../research/markitdown-marker-assessment-20260930.json) exposed a source-derived risk in Skein's existing PDFBox importer. A separate executable regression then confirmed it: two blank pages or whitespace-only pages produced a nonempty separator string instead of the existing no-text notice. The pinned comparison remains an unchanged record of the earlier source-review phase.

`skein-qzci` changes only `PdfImporter.kt` and its import tests. The importer now requires actual nonblank page text before returning an extracted body. Documents with readable text retain every page boundary, including leading, middle and trailing blank pages. Original attachment bytes, metadata title and the source link are retained. No new parser dependency, OCR, model or device operation was introduced.

The same test bytes ran before and after the fix:

| Phase | Actual XML result |
|---|---|
| Old production source, fresh no-build-cache run | 11 tests: 9 passes and the two expected blank/whitespace failures; zero errors/skips. |
| Fixed source, fresh focused run and ktlint | 13 passes, zero failures/errors/skips, including the PDF memory and resource-loader checks. |

The [negative XML](negative-1-actual.xml), three fixed XML files and JSON receipts retain the actual results. The original failing run was not relabeled. Root ran both phases through the exclusive build lease; the worker independently parsed the retained XML before committing `bf6b42091`, integrated as `9d5f7b725`.

Combined local and remote gates for the resulting candidate are tracked in the [resume verification capsule](../2026-09-30-resume-verification/README.md). This fixes blank-text detection only. OCR, typed encrypted/malformed/resource-failure outcomes, structured page/block provenance and converter quality benchmarks remain outside this change. The proposed independent synthetic extraction benchmark is tracked as `skein-8gbi`; existing retrieval labels and thresholds remain unchanged.

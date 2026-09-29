from pathlib import Path
import hashlib,gzip,json,shutil
root=Path(__file__).resolve().parent
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
raw=sorted(p for p in root.rglob('*') if p.is_file() and 'portable' not in p.relative_to(root).parts and p.name!='RAW_SHA256SUMS')
(root/'RAW_SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.relative_to(root)}\n' for p in raw))
out=root/'portable';out.mkdir(exist_ok=False)
transforms=[]
for p in [*raw,root/'RAW_SHA256SUMS']:
 rel=p.relative_to(root)
 if p.suffix in ['.apk','.so']:continue
 if p.name=='archive.zip' and not any(x in str(rel) for x in ['unit-test-results','ux-verification-records']):continue
 if '/files/' in str(rel) and not str(rel).startswith('rb-'):continue
 if p.suffix in ['.log','.json','.tsv'] and p.stat().st_size>8192:
  dest=out/(str(rel)+'.gz');dest.parent.mkdir(parents=True,exist_ok=True)
  dest.write_bytes(gzip.compress(p.read_bytes(),mtime=0))
  transforms.append({'source':str(rel),'source_sha256':sha(p),'source_bytes':p.stat().st_size,'portable':str(dest.relative_to(out)),'portable_sha256':sha(dest),'lossless_gzip':True})
 else:
  dest=out/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
(out/'compression-map.json').write_text(json.dumps(transforms,indent=2)+'\n')
(out/'README.md').write_text('''# Remote evidence review at 8a1fe20b

Exact source: `8a1fe20bd451314596e2f5ea75766f3dc41a64ef`, epoch `1790694605`.
Read `final-run-review.json.gz` for exact jobs, API-verified artifact hashes and boundaries.

- CI 36588129585: 494 XML files, 5167 cases, 5079 pass, 88 skips, zero failures/errors. All 21 populated test tasks came FROM-CACHE; two other tasks had NO-SOURCE. This is not fresh unit execution.
- Screenshots 36588129731: actual eight test tasks execute with --no-build-cache. 145 XML files, 1321 cases, 80 skips, zero failure/error. All 552 individual Roborazzi results are unchanged; all 705 manifest file hashes match. Goldens source tree unchanged since 2e1dcbaf; job-level continue-on-error was not used to conceal a failed step.
- Reproducible 36588129373: two release APKs are identical byte for byte, 100673247 bytes, SHA256 e44c1de591d6d2c3018d158874d727601035418cf99f3515667f636c38e39e15; all 826 ZIP entries match names/order/metadata/bytes. Two cold native builds execute, their AArch64 libraries match byte for byte and match the embedded release library. Negative control did not run; SQLCipher source job was skipped.
- Scheduled retrieval 36584176288 at 321a51fd failed during emulator SDK archive installation before measurement. Failure summary/log only was reviewed. Retrieval and embedding quality gates remain open.
- All 44 local workspace run1 skip identities match prior CI by module, variant, suite and case. The copied run1 report still contains the preserved capture-helper failure; root separately verified the app rerun. This comparison does not overwrite or relabel that failure.

This source predates the new workspace UI. No workspace, fresh ordinary instrumentation, physical-device, installed-candidate, retrieval or embedding acceptance follows from these runs.

Original ZIPs and extracted binary artifacts remain in the owning worker's `build/agent-logs/secondary-followup-8a1fe20b`. This portable copy retains the raw unit/screenshot archives, metadata, reviews, source snapshots and losslessly compressed logs. APK/SO bytes and their large containing ZIPs are intentionally excluded. `RAW_SHA256SUMS` indexes every original file; `SHA256SUMS` indexes this copy. `compression-map.json` maps gzip bytes back to original hashes. All six downloaded ZIP digests matched the GitHub artifact API.

`case-identity-by-variant.json` is the authoritative identity comparison. Earlier `case-identity-comparison.json` groups variants together and reports expected cross-flavor repetition. The original reviewer attempt with an incorrect count assumption about NO-SOURCE tasks is retained as `audit-final-attempt1.py` and its error note; the corrected final audit distinguishes those statuses.
''')
files=sorted(p for p in out.rglob('*') if p.is_file())
(out/'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.relative_to(out)}\n' for p in files))
print(json.dumps({'files':len(files)+1,'bytes':sum(p.stat().st_size for p in out.rglob('*') if p.is_file()),'review_sha256':sha(root/'final-run-review.json'),'portable_index_sha256':sha(out/'SHA256SUMS'),'raw_index_sha256':sha(root/'RAW_SHA256SUMS'),'workspace_skip_review_sha256':sha(root/'workspace-skip-identity-comparison.json')},indent=2))

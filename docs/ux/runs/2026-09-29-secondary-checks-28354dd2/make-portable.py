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
out=root/'portable';out.mkdir(exist_ok=False);mapping=[]
for p in [*raw,root/'RAW_SHA256SUMS']:
 rel=p.relative_to(root)
 if p.suffix in ['.apk','.so']:continue
 if p.name=='archive.zip' and not any(x in str(rel) for x in ['unit-test-results','ux-verification-records']):continue
 if '/files/' in str(rel) and not str(rel).startswith('rb-'):continue
 compress=p.suffix in ['.log','.json','.tsv','.patch'] and p.stat().st_size>8192
 dest=out/(str(rel)+'.gz' if compress else str(rel));dest.parent.mkdir(parents=True,exist_ok=True)
 if compress:dest.write_bytes(gzip.compress(p.read_bytes(),mtime=0))
 else:shutil.copyfile(p,dest)
 assert (gzip.decompress(dest.read_bytes()) if compress else dest.read_bytes())==p.read_bytes()
 mapping.append({'source':str(rel),'source_sha256':sha(p),'source_bytes':p.stat().st_size,'portable':str(dest.relative_to(out)),'portable_sha256':sha(dest),'lossless_gzip':compress})
(out/'copy-provenance.json').write_text(json.dumps(mapping,indent=2)+'\n')
(out/'README.md').write_text('''# Remote source verification at 28354dd2

Exact source: `28354dd25bb4995930ff46a71752f43f8a0d5df3`; source date epoch `1790696685`.
Read `final-run-review.json.gz` for actual job/step conclusions, API-verified artifact hashes, source scope and acceptance boundaries.

- CI **36592770882**: actual **504 XML / 5218 cases / 5130 passed / 88 skipped / zero failures or errors**. Twelve populated test tasks executed, sixteen came FROM-CACHE; task names and status are retained. Do not call the entire lane fresh execution. All 504 manifest file hashes match. All 88 skip identities are unchanged from 8a; there are no duplicate suite/module/variant/class/case identities. The source adds 52 identities and replaces one old NewChat elision test with actual draft-identity acceptance (net +51), documented by the source diff. No removal is concealed as a skip.
- UX screenshots **36592770755**: all eight test and eight verify tasks executed with `--no-build-cache`. Actual **151 XML / 1354 cases / 1274 passed / 80 skipped / zero failures or errors**; all 80 skip identities are unchanged. All **552 individual Roborazzi results are unchanged**, and all **711 source-manifest file hashes** match (151 XML + 552 individual JSON + 8 summaries). The job-level continue-on-error setting did not conceal failed steps. This checks the scoped, reviewed source goldens, including 110 intended updates; it does not validate every tracked PNG or physical rendering.
- Reproducible **36592770702**: both unsigned release APKs match byte for byte: **100740719 bytes**, SHA256 `99edfeebf7828749ad7c47c50d5bbcb4e1ba06d42d5c3b93e22a3edbb1f5cfd8`. All **829** ZIP entry names/order/metadata/content match. Both contexts have the exact source/epoch and different absolute checkout paths.
- The two actual cold native libraries match byte for byte: **25314032 bytes**, SHA256 `2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff`, and match the embedded release library. Retained logs show executed configure/buildCMake tasks without cache restoration; the outer 9 and inner 6 manifest hashes match. No negative-control run is claimed for this attempt. SQLCipher source verification job was explicitly skipped.

All six downloaded ZIP sizes/digests and API source SHAs match. Raw unit/screenshot ZIPs retain the original XML/JSON. Native/APK bytes and their large containing ZIPs remain only in the owning worker's ignored logs; their complete entry hashes, API metadata, contexts and comparisons are here. `RAW_SHA256SUMS` indexes the originals; `SHA256SUMS` indexes this copy; `copy-provenance.json` maps copied/losslessly gzipped bytes to originals. Compression uses fixed mtime=0. The original evidence remains unchanged.

This supports source host regressions and reproducibility. It is **not** installed-candidate or physical-workspace acceptance. The ordinary instrumentation and fold runtime lanes were not reviewed here. Retrieval and embedding quality gates remain open.
''')
files=sorted(p for p in out.rglob('*') if p.is_file())
(out/'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.relative_to(out)}\n' for p in files))
for line in (out/'SHA256SUMS').read_text().splitlines():
 h,rel=line.split('  ',1);assert sha(out/rel)==h
print(json.dumps({'files':len(files)+1,'bytes':sum(p.stat().st_size for p in out.rglob('*') if p.is_file()),'review_sha256':sha(root/'final-run-review.json'),'portable_index_sha256':sha(out/'SHA256SUMS'),'raw_index_sha256':sha(root/'RAW_SHA256SUMS')},indent=2))

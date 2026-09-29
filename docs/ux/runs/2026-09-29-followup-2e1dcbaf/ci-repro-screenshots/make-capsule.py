from pathlib import Path
import json,hashlib,gzip
root=Path(__file__).resolve().parent
capsule=root/'portable';capsule.mkdir(exist_ok=False)
def sha(data):return hashlib.sha256(data).hexdigest()
files=[p for p in root.rglob('*') if p.is_file() and not p.is_relative_to(capsule)]
original_inventory=[{'path':str(p.relative_to(root)),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in sorted(files)]
(root/'retained-file-hashes.json').write_text(json.dumps(original_inventory,indent=2)+'\n')
files.append(root/'retained-file-hashes.json')
latest={sorted(lane.glob('snapshot-*.json'))[-1] for lane in root.glob('*-365*')}
provenance=[];external=[]
for p in sorted(files):
 rel=p.relative_to(root)
 if p.name.startswith('snapshot-') and p not in latest:continue
 parts=rel.parts
 is_artifact=any(x.startswith('artifact-') for x in parts)
 is_large_archive=p.name=='archive.zip' and not any(x.endswith('-unit-test-results') or x.endswith('-ux-verification-records') for x in parts)
 if p.suffix in ['.apk','.so'] or is_large_archive:
  b=p.read_bytes();external.append({'path':str(p.resolve()),'relative_path':str(rel),'bytes':len(b),'sha256':sha(b)});continue
 if 'files' in parts and not any(x.endswith('-so-determinism') or x.endswith('-rb-build-a') or x.endswith('-rb-build-b') for x in parts):
  if p.name not in ['unit-verification.json','screenshot-verification.json']:continue
 b=p.read_bytes()
 if len(b)>10000000:raise ValueError('Unexpected large portable file '+str(rel))
 compress=p.suffix=='.log' or (p.suffix in ['.json','.txt'] and len(b)>20000)
 outrel=Path(str(rel)+('.gz' if compress else ''));dest=capsule/outrel;dest.parent.mkdir(parents=True,exist_ok=True)
 out=gzip.compress(b,mtime=0) if compress else b;dest.write_bytes(out)
 assert (gzip.decompress(out) if compress else out)==b
 provenance.append({'original':str(p.resolve()),'original_bytes':len(b),'original_sha256':sha(b),'copy':str(outrel),'encoding':'gzip' if compress else 'identity','copy_bytes':len(out),'copy_sha256':sha(out),'decoded_bytes_match':True})
(capsule/'COPY_PROVENANCE.json').write_text(json.dumps(provenance,indent=2)+'\n')
(capsule/'EXTERNAL_ARTIFACTS.json').write_text(json.dumps(external,indent=2)+'\n')
(capsule/'SHA256SUMS').write_text(''.join(f'{sha(p.read_bytes())}  {p.relative_to(capsule)}\n' for p in sorted(capsule.rglob('*')) if p.is_file()))
for row in provenance:
 b=(capsule/row['copy']).read_bytes();decoded=gzip.decompress(b) if row['encoding']=='gzip' else b
 assert sha(b)==row['copy_sha256'] and sha(decoded)==row['original_sha256']
print(json.dumps({'capsule':str(capsule),'files':sum(p.is_file() for p in capsule.rglob('*')),'bytes':sum(p.stat().st_size for p in capsule.rglob('*') if p.is_file()),'verified_copies':len(provenance),'external_artifacts':len(external),'original_inventoried_files':len(original_inventory),'sha256sums_sha256':sha((capsule/'SHA256SUMS').read_bytes())},indent=2))

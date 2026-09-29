#!/usr/bin/env python3
import collections, datetime, hashlib, json, pathlib, subprocess, zipfile
root=pathlib.Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
def record(p):
 raw=p.read_bytes();return {'path':str(p),'bytes':len(raw),'sha256':sha(raw)}
def latest(base,suffix):
 p=sorted(base.glob('*-'+suffix+'.json'))[-1];return json.loads(p.read_text()),record(p)
fullraw=subprocess.check_output(['python3',str(root/'review_downloads.py')])
full=json.loads(fullraw)
summary={'source_sha':full['expected_source'],'reviewed_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source_scope':'Host-declared checkout metadata and CI API head SHA; no embedded source or installed APK attestation.','runs':{},'artifacts':[]}
for run in ['36546803273','36546803361']:
 base=root/run
 obj,run_record=latest(base,'run'); jobs,job_record=latest(base,'jobs');inventory,inventory_record=latest(base,'artifacts')
 assert obj['status']=='completed',run
 assert obj['head_sha']==summary['source_sha']
 summary['runs'][run]={'url':obj['html_url'],'status':obj['status'],'conclusion':obj['conclusion'],'raw_run':run_record,'raw_jobs':job_record,'raw_artifacts':inventory_record,'raw_logs':record(base/'run-logs.zip'),'jobs':[{k:j[k] for k in ('id','name','status','conclusion')} for j in jobs['jobs']]}
 for a in full['artifacts']:
  if pathlib.Path(a['path']).parent!=base:continue
  item={k:a[k] for k in ('path','bytes','sha256','zip_entries')}
  published=next(p for p in inventory['artifacts'] if f"artifact-{p['id']}-{p['name']}.zip"==pathlib.Path(a['path']).name)
  assert published['digest']=='sha256:'+a['sha256']
  item['published_digest_matches']=True
  if 'unit' in a:
   u=a['unit'];item['unit']={k:u[k] for k in ['manifest_path','manifest_sha256','manifest_source','source_attribution','verified_files','actual_cases','xml_root_counts','critical_cases']}
   item['unit']['skips_by_class']=dict(collections.Counter(s['class'] for s in u['skips']))
   item['unit']['failures']=u['failures']
  if 'apks' in a:item['apks']=a['apks']
  for k in ('build_context','published_inner_hashes'):
   if k in a:item[k]=a[k]
  summary['artifacts'].append(item)
summary['release_comparison']=full['release_comparison']
summary['candidate_release_status']={'status':'REJECTED_BY_COORDINATOR','reported_reason':'Ordinary instrumentation run 36546817687 has 19 JNI default-supplier/synthetic-native method failures; issue skein-gg11.36.','scope':'Coordinator-reported separate lane; not independently audited in this CI/repro review. These successful source/build checks do not approve installation.'}
summary['ci_guard_findings']={'native_optimization':'34 llama/JNI translation units at O2/O3 in each of two compile_commands records','elf_alignment':'10 build copies inspected by runner; all PT_LOAD >=16384','llama_jni':'27 declared LlamaNative external functions, exact Java_ surface in 5 build copies','sqlite_jni':'25 declared SkeinSQLiteNativeImpl external functions, exact Java_ surface in 5 build copies','content_logging':'19 source files clean','manifest_audit':'PASS debug APK','model_manifests':'24 manifests checked, 0 shipped'}
summary['unit_local_comparison']={'local_previously_audited':{'passed':5079,'skipped':86,'failures':0,'errors':0},'remote':{'passed':5077,'skipped':88,'failures':0,'errors':0},'additional_remote_skips':{'test':'app.skein.MainActivityComposeTest.retrying after a failed open brings the shell up once the vault opens','variants':['DevDebug','FossDebug'],'reason':'skein-lds9: skipped on CI, runs locally','source':'app/src/test/kotlin/app/skein/MainActivityComposeTest.kt:550'}}
ci=root/'36546803273'
z=zipfile.ZipFile(ci/'run-logs.zip'); guard_records=[]
for name in z.namelist():
 if name.count('/')==1 and any(name.split('/')[1].startswith(str(n)+'_') for n in range(16,23)):
  raw=z.read(name);filename=name.split('/')[-1];p=ci/('guard-'+filename)
  with p.open('xb') as f:f.write(raw)
  r=record(p);r['zip_member']=name;r['result_lines']=[line for line in raw.decode().splitlines() if ('[ok]' in line or 'PASS' in line or 'expected' in line or 'external' in line or ': ok:' in line or ': clean:' in line or ': OK' in line or ': checked' in line) and '\x1b' not in line];guard_records.append(r)
summary['ci_guard_logs']=guard_records
assert len(guard_records)==7,len(guard_records)
native=root/'36546803361'/'job-109335143821-native-determinism.log'
summary['native_cold_build']={'raw_log':record(native),'builds':2,'runner_reported_sha256_each':'2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff','independently_rehashed_release_apk_llama_matches':True,'scope':'Success cold-build .so copies were not uploaded; both retained release APK libraries independently match the two runner-reported cold-build hashes.'}
summary['remaining_scope']=['SQLCipher pinned-source regeneration job skipped: tag-only. It did not pass in this run.','Original reproducibility Gradle invocations lacked --max-workers=2; preserved original evidence. Later corrected runs are outside this review.','This is one source commit, not evidence by itself for three consecutive main commits.','No physical device, emulator, runtime retrieval-quality, embedding-quality, IME/focus, or full fold acceptance claims.']
with (root/'review-final-full.json').open('xb') as f:f.write(fullraw)
summary['full_review']=record(root/'review-final-full.json')
raw=(json.dumps(summary,indent=2)+'\n').encode()
with (root/'review-summary.json').open('xb') as f:f.write(raw)
print(json.dumps(record(root/'review-summary.json'),indent=2))

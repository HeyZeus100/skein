from pathlib import Path
from collections import Counter
import hashlib,json,subprocess,zipfile
root=Path(__file__).resolve().parent
source='28354dd25bb4995930ff46a71752f43f8a0d5df3'
prior='8a1fe20bd451314596e2f5ea75766f3dc41a64ef'
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
runs=[];artifacts=[]
for lane,run in [('ci',36592770882),('screenshots',36592770755),('rb',36592770702)]:
 d=root/f'{lane}-{run}';sp=sorted(d.glob('snapshot-*.json'))[-1];s=json.loads(sp.read_text())
 assert s['run']['head_sha']==source and s['run']['status']=='completed' and s['run']['conclusion']=='success'
 jobs=s['jobs']['jobs'];assert all(j['conclusion'] in ['success','skipped'] for j in jobs)
 assert all(step['conclusion'] in ['success','skipped'] for j in jobs for step in j['steps'])
 runs.append({'lane':lane,'id':run,'source':source,'snapshot':str(sp.relative_to(root)),'conclusion':s['run']['conclusion'],'jobs':[{'id':j['id'],'name':j['name'],'conclusion':j['conclusion'],'steps':[{'name':x['name'],'conclusion':x['conclusion']} for x in j['steps']]} for j in jobs]})
 for a in sorted(d.glob('artifact-*')):
  m=json.loads((a/'metadata.json').read_text());archive=a/'archive.zip';h=digest(archive)
  assert m['digest']=='sha256:'+h and m['size_in_bytes']==archive.stat().st_size and m['workflow_run']['head_sha']==source
  inventory=[]
  with zipfile.ZipFile(archive) as z:
   assert len(z.namelist())==len(set(z.namelist()))
   for i in z.infolist():
    if i.is_dir():continue
    p=a/'files'/i.filename;assert p.is_file() and p.stat().st_size==i.file_size
    inventory.append({'path':i.filename,'bytes':p.stat().st_size,'sha256':digest(p)})
  (a/'extracted-file-inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
  artifacts.append({'id':m['id'],'name':m['name'],'zip':str(archive.relative_to(root)),'bytes':m['size_in_bytes'],'sha256':h,'api_digest_valid':True,'api_source_valid':True,'extracted_files':len(inventory),'extracted_file_inventory':str((a/'extracted-file-inventory.json').relative_to(root))})
ci=json.loads((root/'ci-36592770882/unit-summary.json').read_text());ss=json.loads((root/'screenshots-36592770755/evidence-review.json').read_text())
ci_log=json.loads((root/'ci-36592770882/job-log-review.json').read_text());ss_log=json.loads((root/'screenshots-36592770755/job-log-review.json').read_text())
assert ci['counts']=={'tests':5218,'passed':5130,'skipped':88,'failures':0,'errors':0}
assert ci_log['populated_test_task_states']=={'FROM-CACHE':16,'EXECUTED':12}
assert ss_log['populated_test_task_states']=={'EXECUTED':8} and len(ss_log['verify_task_lines'])==8
assert ss_log['no_build_cache_lines'] and not ss['issues']
for lane,run in [('ci',36592770882),('screenshots',36592770755)]:
 d=json.loads((root/f'{lane}-{run}'/'case-identity-by-variant.json').read_text())
 assert not d['duplicates'] and not d['new_skips'] and not d['removed_skips']
 assert len(d['missing'])==(1 if lane=='ci' else 0)
 assert len(d['new'])==(52 if lane=='ci' else 33)
 if lane=='ci':assert d['missing'][0][2]=='the new-chat landing over the Chat root is elided wherever the root already shows the landing'
patch=subprocess.check_output(['git','diff',prior,source,'--','core/navigation/src/test/kotlin/app/skein/core/navigation/NavigatorBackTest.kt'])
(root/'navigator-test-identity-change.patch').write_bytes(patch)
assert b'+    fun `an explicit new chat keeps its rendered draft identity in every mode`' in patch
(root/'reviewer-attempt-note.txt').write_text('Initial interactive identity comparison asserted no removals and failed before publishing a result. Actual source review confirms one intentional replacement of the old NewChat elision assertion with the new actual-draft-identity assertion plus two added tests. Original raw XML retained. Authoritative case-identity-by-variant.json reports this replacement; no skip or failure is relabeled.\n')
rb=root/'rb-36592770702';release=json.loads((rb/'release-byte-comparison.json').read_text());native=next(rb.glob('artifact-*-so-determinism/files'))
libs=sorted(native.glob('run.*/*.so'));assert len(libs)==2 and libs[0].read_bytes()==libs[1].read_bytes()
native_logs=json.loads((rb/'native-log-cache-review.json').read_text());cold=[x for x in native_logs if Path(x['path']).name.startswith('build-')];assert len(cold)==2
assert all(x['native_tasks'] and not x['from_cache_tasks'] and not x['failed_build_markers'] for x in cold)
assert all(not any(t in line for t in ['UP-TO-DATE','SKIPPED','FROM-CACHE']) for x in cold for line in x['native_tasks'])
epoch=subprocess.check_output(['git','show','-s','--format=%ct',source]).decode().strip();assert release['source_date_epoch']==epoch
assert len({c['abs_path'] for c in release['contexts']})==2
names=[x.split('\t')[-1] for x in (root/'source-scope-names.txt').read_text().splitlines()]
production=[x for x in names if '/src/main/' in x or '/src/foss/' in x]
assert not any(x.startswith(('inference-service/','embedder-service/','core/inference/')) for x in names)
goldens=[x for x in names if x.startswith('ux-baselines/')];assert len(goldens)==110
assert Counter(x.split('/')[1] for x in goldens)=={'core-designsystem':36,'feature-chat':56,'feature-editor':18}
result={'source_sha':source,'source_date_epoch':epoch,'runs':runs,'artifacts':artifacts,'unit':{'xml_files':ci['xml_files'],'all_manifest_hashes_valid':True,'manifest_source_attribution':ci['manifest_source_attribution'],'counts':ci['counts'],'populated_test_task_states':ci_log['populated_test_task_states'],'case_identity_comparison':{'prior_run':36588129585,'added':52,'replaced_prior_name':1,'duplicates':0,'skip_identities_unchanged':True,'source_diff':'navigator-test-identity-change.patch'},'fresh_execution':'12 populated test tasks executed; 16 restored FROM-CACHE. Exact task lines retained. This is not all-fresh unit execution.'},'screenshots':{'xml_files':ss['xml']['files'],'counts':ss['xml']['actual_testcase_counts'],'all_manifest_hashes_valid':True,'manifest_hashes_verified':ss['source_manifest']['verified_files'],'result_types':ss['roborazzi']['actual_types'],'executed_test_tasks':8,'executed_verify_tasks':8,'no_build_cache':True,'actual_job_and_steps_success':True,'skip_identities_unchanged_from_8a':True,'duplicates':0,'added_case_identities':33,'matches_local_screens2_case_identities':True,'golden_changes_since_8a':{'total':110,'core-designsystem':36,'feature-chat':56,'feature-editor':18},'golden_changes_claim':'Previously reviewed/scoped recorded source goldens; remote actual552 unchanged verifies those source baselines, not every tracked PNG or physical device.'},'reproducible':{'whole_apk_bytes_equal':True,'apk_bytes':release['release_apks'][0]['bytes'],'apk_sha256':release['release_apks'][0]['sha256'],'apk_entry_count':release['entry_count'],'apk_entry_names_order_metadata_contents_equal':True,'cold_native_libraries_equal_bytes':True,'native_bytes':libs[0].stat().st_size,'native_sha256':digest(libs[0]),'native_build_count':2,'cold_native_task_execution':True,'embedded_release_llama_matches_native':True,'different_absolute_checkout_paths':True,'negative_control_not_run_this_attempt':True,'sqlcipher_source_job_skipped':True},'source_scope':{'compared_to':prior,'production_files_changed':production,'inference_implementation_unchanged':True,'golden_only_commit':source},'boundaries':{'new_workspace_source_host_regression':True,'full_physical_workspace_acceptance':False,'installed_candidate_attestation':False,'ordinary_instrumentation_reviewed_here':False,'fold_runtime_gate_reviewed_here':False,'retrieval_or_embedding_quality_pass':False},'issues':[]}
(root/'final-run-review.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'review_sha256':digest(root/'final-run-review.json'),'artifacts':len(artifacts),'source_sha':source,'counts':ci['counts'],'native_sha256':digest(libs[0]),'apk_sha256':release['release_apks'][0]['sha256']},indent=2))

from pathlib import Path
from collections import Counter
import json,hashlib,re,subprocess,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent
prior=root.parent/'secondary-followup-2e1dcbaf'
source='8a1fe20bd451314596e2f5ea75766f3dc41a64ef'
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def identities(base):
 out=Counter();skips=Counter()
 for p in base.glob('artifact-*/files/**/*.xml'):
  relative=str(p).split('/files/')[1]
  if '/build/test-results/' not in relative:continue
  for c in ET.parse(p).getroot().iter('testcase'):
   k=(relative,c.get('classname'),c.get('name'));out[k]+=1
   if c.find('skipped') is not None:skips[k]+=1
 return out,skips
runs=[];artifacts=[]
for lane,run,oldrun in [('ci',36588129585,36559029276),('screenshots',36588129731,36559029446),('rb',36588129373,36559029462)]:
 d=root/f'{lane}-{run}'
 snap=json.loads(next(d.glob('snapshot-*.json')).read_text())
 assert snap['run']['head_sha']==source
 assert snap['run']['status']=='completed'
 jobs=snap['jobs']['jobs']
 runs.append({'lane':lane,'id':run,'source':source,'conclusion':snap['run']['conclusion'],'jobs':[{'id':j['id'],'name':j['name'],'conclusion':j['conclusion'],'steps':[{'name':s['name'],'conclusion':s['conclusion']} for s in j['steps']]} for j in jobs]})
 for a in d.glob('artifact-*'):
  m=json.loads((a/'metadata.json').read_text());zipsha=digest(a/'archive.zip')
  assert m['digest']=='sha256:'+zipsha
  assert m['size_in_bytes']==(a/'archive.zip').stat().st_size
  assert m['workflow_run']['head_sha']==source
  artifacts.append({'id':m['id'],'name':m['name'],'zip':str((a/'archive.zip').relative_to(root)),'bytes':m['size_in_bytes'],'sha256':zipsha,'api_digest_valid':True,'api_source_valid':True})
 if lane!='rb':
  actual,skips=identities(d);old,oldskips=identities(prior/f'{lane}-{oldrun}')
  diff={'identity_includes_suite_path_and_variant':True,'tests':sum(actual.values()),'unique':len(actual),'duplicates':[list(k)+[v] for k,v in actual.items() if v>1],'new':[list(k)+[v] for k,v in (actual-old).items()],'missing':[list(k)+[v] for k,v in (old-actual).items()],'new_skips':[list(k)+[v] for k,v in (skips-oldskips).items()],'prior_run':oldrun,'note':'Earlier module-only identity grouping intentionally omitted flavor and produced expected cross-flavor repetitions; use this variant-aware report.'}
  assert not diff['duplicates'] and not diff['new'] and not diff['missing'] and not diff['new_skips']
  (d/'case-identity-by-variant.json').write_text(json.dumps(diff,indent=2)+'\n')
rb=root/'rb-36588129373'
native=next(rb.glob('artifact-*-so-determinism/files'))
libs=sorted(native.glob('run.*/*.so'));assert len(libs)==2 and libs[0].read_bytes()==libs[1].read_bytes()
logs=[]
for p in list(rb.glob('job-*.log'))+list(native.glob('run.*/build-*.log')):
 text=p.read_text(errors='replace')
 tasks=[s for s in text.splitlines() if '> Task ' in s]
 native_tasks=[s for s in tasks if 'buildCMake' in s or 'configureCMake' in s]
 cached=[s for s in tasks if 'FROM-CACHE' in s]
 logs.append({'path':str(p.relative_to(root)),'sha256':digest(p),'native_tasks':native_tasks,'from_cache_tasks':cached,'failed_build_markers':[s for s in text.splitlines() if 'BUILD FAILED' in s],'source_epoch_lines':[s for s in text.splitlines() if 'SOURCE_DATE_EPOCH=' in s or 'source_date_epoch=' in s],'no_build_cache_lines':[s for s in text.splitlines() if '--no-build-cache' in s]})
 if p.name.startswith('build-'):
  assert 'BUILD SUCCESSFUL' in text and 'BUILD FAILED' not in text and not cached
  assert any('buildCMake' in s for s in native_tasks)
  assert all(not any(x in s for x in ['UP-TO-DATE','SKIPPED','FROM-CACHE']) for s in native_tasks)
(rb/'native-log-cache-review.json').write_text(json.dumps(logs,indent=2)+'\n')
ci=json.loads((root/'ci-36588129585/unit-summary.json').read_text())
ss=json.loads((root/'screenshots-36588129731/evidence-review.json').read_text())
release=json.loads((rb/'release-byte-comparison.json').read_text())
ci_log=json.loads((root/'ci-36588129585/job-log-review.json').read_text())
ss_log=json.loads((root/'screenshots-36588129731/job-log-review.json').read_text())
assert len(ci_log['test_tasks_not_executed'])==21
assert all('FROM-CACHE' in s or 'NO-SOURCE' in s for s in ci_log['test_task_lines'])
assert not ss_log['test_tasks_not_executed']
changes=(root/'source-scope-names.txt').read_text().splitlines()
non_docs=[x for x in changes if not x.split('\t')[-1].startswith('docs/')]
production=[x for x in non_docs if '/src/main/' in x or '/src/foss/' in x]
assert not production
assert not (root/'golden-diff.txt').read_bytes()
result={'source_sha':source,'source_date_epoch':subprocess.check_output(['git','show','-s','--format=%ct',source]).decode().strip(),'runs':runs,'artifacts':artifacts,'unit':{'xml_files':ci['xml_files'],'all_manifest_hashes_valid':True,'counts':ci['counts'],'test_task_count':len(ci_log['test_task_lines']),'test_tasks_from_cache':21,'test_tasks_no_source':sum('NO-SOURCE' in s for s in ci_log['test_task_lines']),'identities_and_skips_equal_prior_2e':True,'fresh_execution':False},'screenshots':{'xml_files':ss['xml']['files'],'xml_counts':ss['xml']['actual_testcase_counts'],'manifest_hashes_verified':ss['source_manifest']['verified_files'],'result_types':ss['roborazzi']['actual_types'],'test_tasks_executed':len([x for x in ss_log['test_task_lines'] if ':test' in x]),'no_build_cache':True,'continued_error_job_actual_steps_success':True,'golden_git_tree_unchanged_since_2e':True},'reproducible':{'whole_apk_bytes_equal':release['whole_apk_bytes_equal'],'apk_bytes':release['release_apks'][0]['bytes'],'apk_sha256':release['release_apks'][0]['sha256'],'apk_entry_count':release['entry_count'],'apk_entry_names_order_metadata_bytes_equal':True,'cold_native_libraries_equal_bytes':True,'native_bytes':libs[0].stat().st_size,'native_sha256':digest(libs[0]),'native_build_count':2,'native_cold_build_logs_no_cache_native_tasks_executed':True,'embedded_release_llama_matches_native':True,'different_absolute_checkout_paths':True,'negative_control_not_run_in_this_attempt':True,'sqlcipher_source_job_skipped':True},'scope':{'production_source_unchanged_from':'2e1dcbafd645d4bb9348340905949bc5cae90acf','non_document_changes':non_docs,'workspace_acceptance':False,'installed_candidate_attestation':False,'retrieval_or_embedding_quality_pass':False},'scheduled_retrieval':{'run_id':36584176288,'source_sha':'321a51fdb0d7f6624e0fb957b819b968965d5e3f','conclusion':'failure','summary':'Emulator SDK installation failed with Error on ZipFile unknown archive before measurement; cleanup then reported emulator5554 TCP connection refused. Failure-summary-only review, no metric/artifact acceptance.'},'issues':[]}
(root/'final-run-review.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in ['runs','scope']},indent=2))

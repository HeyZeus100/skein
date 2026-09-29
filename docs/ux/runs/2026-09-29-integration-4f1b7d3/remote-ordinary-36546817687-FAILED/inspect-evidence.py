from pathlib import Path
from collections import Counter
import hashlib, json, xml.etree.ElementTree as ET
r=Path(__file__).resolve().parent
x=r/'connected-test-reports-11024250039'
sha='4f1b7d334173544feb47190b3e2e7efc4a5e1688'
manifest_path=x/'build/instrumentation-verification.json'
m=json.loads(manifest_path.read_text())
issues=[]; integrity=[]; verified=[]
if m['source_sha']!=sha: integrity.append('source mismatch')
for f in m['files']:
 p=x/f['path']
 if not p.is_file():integrity.append('missing '+f['path']);continue
 b=p.read_bytes()
 if len(b)!=f['bytes'] or hashlib.sha256(b).hexdigest()!=f['sha256']:integrity.append('size/hash mismatch '+f['path'])
 else:verified.append(f['path'])
mods={}; allcases={}; failures=[]; identities=set(); classes=Counter()
xml_files=list(x.rglob('*.xml'))
for module in ['app','core/vault','inference-service']:
 paths=sorted((x/module/'build/outputs/androidTest-results/connected').rglob('*.xml'))
 result={'files':[str(p.relative_to(x)) for p in paths],'tests':0,'passed':0,'failures':0,'errors':0,'skipped':0,'duplicate_identities':[],'missing_identities':[]}
 for p in paths:
  node=ET.parse(p).getroot(); actual=Counter({'tests':0,'failures':0,'errors':0,'skipped':0})
  for case in node.iter('testcase'):
   identity=(case.get('classname'),case.get('name')); actual['tests']+=1;result['tests']+=1; classes[identity[0]]+=1
   if not all(identity):result['missing_identities'].append(identity)
   if identity in identities:result['duplicate_identities'].append(identity)
   identities.add(identity)
   tags=[t for t in ['failure','error','skipped'] if case.find(t)is not None]
   record={'module':module,'classname':identity[0],'name':identity[1],'xml':str(p.relative_to(x)),'time':case.get('time'),'status':'passed' if not tags else '+'.join(tags)}
   allcases[identity]=record
   if not tags:result['passed']+=1
   for tag in tags:
    k={'failure':'failures','error':'errors','skipped':'skipped'}[tag];actual[k]+=1;result[k]+=1
    e=case.find(tag);failures.append(dict(record,kind=tag,message=e.get('message'),trace=e.text or ''))
  for k,v in actual.items():
   if int(node.get(k,0))!=v:issues.append('declared/actual mismatch '+str(p.relative_to(x))+':'+k)
 if not result['tests']:issues.append('missing module cases '+module)
 if result['duplicate_identities'] or result['missing_identities']:issues.append('duplicate/missing identities '+module)
 mods[module]=result
expected_xml={str(p.relative_to(x)) for p in xml_files}
recorded_xml={f['path'] for f in m['files'] if f['path'].endswith('.xml')}
if expected_xml!=recorded_xml:integrity.append('manifest XML coverage mismatch')
required=[
 ('app.skein.inference.service.LlamaNativeTest','descriptorLoadCancellationFromProgressClosesOwnedStreamAndKeepsCallerFd'),
 ('app.skein.inference.service.LlamaNativeTest','pathLoadCancellationFromProgressRetainsNoNativeHandle'),
 ('app.skein.inference.service.InferenceServiceInstrumentedTest','lockingMidGenerationCancelsThenLockedTerminatesTheIsolatedProcess'),
 ('app.skein.inference.service.InferenceServiceInstrumentedTest','aLockingServiceRefusesTheNextGenerateBeforeProcessTeardown'),
 ('app.skein.inference.service.InferenceServiceInstrumentedTest','staleLockedPushDoesNotKillNewlyAuthorizedService'),
 ('app.skein.inference.LlamaCppEngineInstrumentedTest','lockAfterInspectionTerminatesTheProcessAndOnlyANewUnlockCanRebind'),
]
targets=[]
for identity in required:
 if identity not in allcases:
  issues.append('missing required case '+'.'.join(identity));targets.append({'classname':identity[0],'name':identity[1],'status':'missing'})
 else:targets.append(allcases[identity])
remote_review=json.loads((x/'build/instrumentation-review.json').read_text())
for module,d in mods.items():
 for k in ['tests','passed','failures','errors','skipped']:
  if remote_review['modules'][module][k]!=d[k]:issues.append('remote reviewer mismatch '+module+':'+k)
if any(f['classname']=='app.skein.core.vault.eval.RealRetrievalEvaluationTest' for f in allcases.values()):issues.append('opt-in retrieval class appeared')
a=json.loads((r/'artifacts-final.json').read_text())['artifacts'][0];p=r/f"{a['name']}-{a['id']}.zip";digest=hashlib.sha256(p.read_bytes()).hexdigest()
if a['digest']!='sha256:'+digest or a['size_in_bytes']!=p.stat().st_size:integrity.append('artifact API mismatch')
run=json.loads((r/'status-03.json').read_text())
failure_path=r/'failure-details.json';failure_path.write_text(json.dumps(failures,indent=2)+'\n')
summary={
 'run_id':36546817687,'run_url':run['url'],'attempt':run['attempt'],'source_sha':sha,'run_status':run['status'],'run_conclusion':run['conclusion'],
 'step_conclusions':[{'job':j['name'],'job_conclusion':j['conclusion'],'steps':[{k:s[k] for k in ['number','name','status','conclusion']} for s in j['steps']]} for j in run['jobs']],
 'artifact':{'id':a['id'],'name':a['name'],'zip_bytes':p.stat().st_size,'zip_sha256':digest,'matches_api_digest':a['digest']=='sha256:'+digest},
 'source_manifest':{'path':str(manifest_path.relative_to(x)),'sha256':hashlib.sha256(manifest_path.read_bytes()).hexdigest(),'source_sha':m['source_sha'],'recorded_files':len(m['files']),'verified_files':len(verified),'all_actual_xml_recorded':expected_xml==recorded_xml,'built_apks_host_declared':m['built_apks'],'built_apk_limitation':'Built APKs are listed by the host manifest but not retained in this artifact; installed bytes were not independently measured.'},
 'modules':mods,'totals':{k:sum(d[k] for d in mods.values()) for k in ['tests','passed','failures','errors','skipped']},'six_required_cases':targets,
 'failure_class_counts':dict(Counter(f['classname'] for f in failures)),
 'failure_signature_counts':dict(Counter(('loadModelFromFd$lambda$0' if 'loadModelFromFd$lambda$0' in f['trace'] else 'loadModel$lambda$0' if 'loadModel$lambda$0' in f['trace'] else f['trace'].split('\n')[0]) for f in failures)),
 'failure_details':{'path':failure_path.name,'sha256':hashlib.sha256(failure_path.read_bytes()).hexdigest()},
 'integrity_issues':integrity,'review_issues':issues,
 'acceptance_passed':not integrity and not issues and not failures,
 'source_fix_required':'Default BooleanSupplier lambdas on external loadModel/loadModelFromFd resolve as native methods on ART (UnsatisfiedLinkError). Inference owner must fix and rerun; 19 failures cannot be waived.',
}
(r/'evidence-review.json').write_text(json.dumps(summary,indent=2)+'\n')
print('Integrity issues',integrity,'Review issues',issues)
print('Totals',summary['totals'])
for item in targets:print(item['status'],item['classname'],item['name'])
print('Failure signatures',summary['failure_signature_counts'])

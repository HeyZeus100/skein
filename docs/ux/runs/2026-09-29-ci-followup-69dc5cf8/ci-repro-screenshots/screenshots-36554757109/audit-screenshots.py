from pathlib import Path
from collections import Counter
import hashlib, json, xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
artifact_directory = next(root.glob('artifact-*-ux-verification-records'))
extracted = artifact_directory / 'files'
manifest = json.loads((extracted / 'build/screenshot-verification.json').read_text())
sha = '69dc5cf8d6ca87414cce1808e6d71bf0c43e3567'
issues = []
if manifest['source_sha'] != sha: issues.append('source mismatch')
recorded_paths = set()
verified = 0
for entry in manifest['files']:
    relative = entry['path']
    if relative in recorded_paths: issues.append('duplicate manifest entry: ' + relative)
    recorded_paths.add(relative)
    path = extracted / relative
    if not path.is_file():
        issues.append('missing: ' + relative)
        continue
    data = path.read_bytes()
    if len(data) != entry['bytes'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
        issues.append('hash/size mismatch: ' + relative)
    else: verified += 1
actual_paths = {str(p.relative_to(extracted)) for p in extracted.rglob('*') if p.is_file() and p.name != 'screenshot-verification.json'}
for p in sorted(actual_paths - recorded_paths): issues.append('unlisted: ' + p)
for p in sorted(recorded_paths - actual_paths): issues.append('absent: ' + p)

xml_totals = Counter()
suites = []
skipped = []
failures = []
for path in sorted(extracted.rglob('*.xml')):
    node = ET.parse(path).getroot()
    cases = node.findall('.//testcase')
    counts = Counter({'tests': len(cases), 'failures': 0, 'errors': 0, 'skipped': 0})
    for case in cases:
        identity = {'xml': str(path.relative_to(extracted)), 'classname': case.get('classname'), 'name': case.get('name')}
        for kind in ['failure', 'error', 'skipped']:
            elements = case.findall(kind)
            if elements:
                counts[{'failure':'failures','error':'errors','skipped':'skipped'}[kind]] += 1
                record = dict(identity, kind=kind, details=[dict(message=e.get('message'), text=e.text) for e in elements])
                (skipped if kind == 'skipped' else failures).append(record)
    declared = {k: int(node.get(k, 0)) for k in ['tests','failures','errors','skipped']}
    if declared != dict(counts): issues.append('XML declared mismatch: ' + str(path.relative_to(extracted)))
    xml_totals.update(counts)
    suites.append({'path': str(path.relative_to(extracted)), 'name': node.get('name'), 'counts': dict(counts), 'declared': declared})

actual_results = Counter()
modules = []
for path in sorted(extracted.rglob('results-summary.json')):
    data = json.loads(path.read_text())
    module = str(path.relative_to(extracted)).split('/build/')[0]
    individual_files = sorted((path.parent / 'results').glob('*.json'))
    individual = [json.loads(p.read_text()) for p in individual_files]
    counts = Counter(r.get('type','MISSING_TYPE') for r in individual)
    summary_counts = Counter(r.get('type','MISSING_TYPE') for r in data['results'])
    individual_exact = Counter(json.dumps(r, sort_keys=True) for r in individual)
    summary_exact = Counter(json.dumps(r, sort_keys=True) for r in data['results'])
    if individual_exact != summary_exact: issues.append('Roborazzi summary differs from individual results: ' + module)
    if data['summary']['total'] != len(individual): issues.append('Roborazzi total differs: ' + module)
    for kind in ['recorded','added','changed','unchanged']:
        if data['summary'].get(kind,0) != counts[kind]: issues.append('Roborazzi declared type differs: '+module+':'+kind)
    for r in individual:
        if r.get('type') != 'unchanged': issues.append('Roborazzi nonunchanged: ' + json.dumps(r, sort_keys=True))
    actual_results.update(counts)
    modules.append({'module':module, 'individual_result_files':len(individual_files), 'actual_types':dict(counts), 'summary_array_types':dict(summary_counts), 'declared_summary':data['summary'], 'exact_summary_matches_individual': individual_exact == summary_exact})

snapshot=json.loads(sorted(root.glob('snapshot-*.json'))[-1].read_text())
run=snapshot['run']
metadata=snapshot['artifacts']
archives=[]
for artifact in metadata['artifacts']:
    directory=root/f"artifact-{artifact['id']}-{artifact['name']}"
    p=directory/'archive.zip'
    digest=hashlib.sha256(p.read_bytes()).hexdigest()
    size=p.stat().st_size
    digest_matches=artifact.get('digest') == 'sha256:'+digest
    if not digest_matches or size != artifact['size_in_bytes']: issues.append('artifact ZIP metadata mismatch: '+p.name)
    if artifact['workflow_run']['head_sha'] != sha: issues.append('artifact source SHA mismatch')
    archives.append({'path':str(p.relative_to(root)),'id':artifact['id'],'name':artifact['name'],'bytes':size,'sha256':digest,'api_digest':artifact.get('digest'),'api_digest_matches':digest_matches,'api_size_matches':size==artifact['size_in_bytes']})

review = {
    'run_id':36554757109, 'run_url':run['html_url'], 'attempt':run['run_attempt'], 'source_sha':sha,
    'run_status':run['status'], 'run_conclusion':run['conclusion'], 'jobs':snapshot['jobs']['jobs'],
    'source_manifest':{'source_sha':manifest['source_sha'],'lane':manifest['lane'],'recorded_files':len(manifest['files']),'verified_files':verified,'manifest_sha256':hashlib.sha256((extracted/'build/screenshot-verification.json').read_bytes()).hexdigest()},
    'artifacts':archives,'xml':{'files':len(suites),'actual_testcase_counts':dict(xml_totals),'suites':suites,'failures_and_errors':failures,'skipped_testcases':skipped},
    'roborazzi':{'actual_types':dict(actual_results),'individual_results':sum(actual_results.values()),'modules':modules},
    'issues':issues,
    'limitations':['Screenshot lane is host-declared source evidence, not installed APK attestation.','Skipped XML cases remain unexecuted; screenshot unchanged results alone do not replace device/instrumentation acceptance.']
}
(root/'evidence-review.json').write_text(json.dumps(review,indent=2)+'\n')
print(json.dumps({k:review[k] for k in ['run_id','source_sha','run_status','run_conclusion','source_manifest','artifacts','issues']},indent=2))
print('XML',len(suites),dict(xml_totals),'Roborazzi',dict(actual_results))
print('Skip groups',dict(Counter(x['classname'] for x in skipped)))

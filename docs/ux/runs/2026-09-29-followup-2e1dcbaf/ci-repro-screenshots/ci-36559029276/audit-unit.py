import hashlib,json,pathlib,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parent
expected='2e1dcbafd645d4bb9348340905949bc5cae90acf'
directory=next(root.glob('artifact-*-unit-test-results'))
meta=json.loads((directory/'metadata.json').read_text())
assert meta['workflow_run']['head_sha']==expected
archive_sha=hashlib.sha256((directory/'archive.zip').read_bytes()).hexdigest()
assert meta.get('digest')=='sha256:'+archive_sha
files=directory/'files'
manifest_path=next(files.rglob('unit-verification.json'))
manifest=json.loads(manifest_path.read_text())
assert manifest['source_sha']==expected
assert manifest['lane']=='unit'
assert not manifest['built_apks']
records=[]
skips=[]
failures=[]
guards=[]
total={'tests':0,'passed':0,'skipped':0,'failures':0,'errors':0}
for entry in manifest['files']:
    p=files/entry['path']
    assert p.is_file(),str(p)
    raw=p.read_bytes()
    assert len(raw)==entry['bytes']
    assert hashlib.sha256(raw).hexdigest()==entry['sha256']
    xml=ET.fromstring(raw)
    cases=list(xml.iter('testcase'))
    actual={k:0 for k in total}
    for case in cases:
        actual['tests']+=1
        issue=None
        for tag in ('failure','error','skipped'):
            children=case.findall(tag)
            if children:
                bucket={'failure':'failures','error':'errors','skipped':'skipped'}[tag]
                actual[bucket]+=1
                issue=tag
                item={'path':entry['path'],'class':case.get('classname'),'name':case.get('name'),'kind':tag,'details':[(e.attrib,e.text) for e in children]}
                (skips if tag=='skipped' else failures).append(item)
                break
        if not issue:actual['passed']+=1
        if 'NativeSurfaceTest' in (case.get('classname') or ''):
            guards.append({'path':entry['path'],'class':case.get('classname'),'name':case.get('name'),'outcome':issue or 'pass'})
    declared={k:int(xml.get(k,'0')) for k in ('tests','skipped','failures','errors')}
    assert declared=={k:actual[k] for k in declared},(entry['path'],declared,actual)
    for key in total:total[key]+=actual[key]
    records.append({'path':entry['path'],'sha256':entry['sha256'],'bytes':entry['bytes'],**actual})
manifest_paths={entry['path'] for entry in manifest['files']}
actual_xml={str(p.relative_to(files)) for p in files.rglob('*.xml') if '/build/test-results/' in '/'+str(p.relative_to(files))}
assert manifest_paths==actual_xml,{'missing':list(actual_xml-manifest_paths),'extra':list(manifest_paths-actual_xml)}
result={'run_id':36559029276,'source_sha':expected,'artifact_id':meta['id'],'archive_sha256':archive_sha,'manifest_source_attribution':manifest['source_attribution'],'xml_files':len(records),'counts':total,'guard_cases':guards,'failures':failures,'skips':skips,'xml':records}
print(json.dumps(result,indent=2))

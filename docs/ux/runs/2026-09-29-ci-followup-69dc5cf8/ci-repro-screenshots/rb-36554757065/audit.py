import hashlib, json, pathlib, re, struct, sys, zipfile
root=pathlib.Path(__file__).resolve().parent
expected='69dc5cf8d6ca87414cce1808e6d71bf0c43e3567'

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
def parse_context(path):
    return dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line)
def verify_manifest(path):
    entries=[]
    for line in path.read_text().splitlines():
        match=re.fullmatch(r'([0-9a-f]{64}) [ *](.+)',line)
        if not match:raise AssertionError('Malformed manifest line: '+line)
        target=(path.parent/match[2]).resolve()
        assert target.is_relative_to(path.parent.resolve()),'Manifest traversal'
        actual=sha(target)
        assert actual==match[1],str(target)+' mismatch'
        entries.append({'path':str(target.relative_to(path.parent.resolve())),'sha256':actual,'bytes':target.stat().st_size})
    assert entries,'Empty manifest'
    return entries

result={'source_sha':expected,'artifacts':[]}
for directory in sorted(root.glob('artifact-*')):
    meta=json.loads((directory/'metadata.json').read_text())
    if not (directory/'archive-verification.json').exists():continue
    package={'id':meta['id'],'name':meta['name'],'archive_sha256':sha(directory/'archive.zip'),'files':[]}
    assert meta['workflow_run']['head_sha']==expected
    if meta.get('digest'): assert meta['digest']=='sha256:'+package['archive_sha256']
    files=directory/'files'
    if meta['name']=='so-determinism':
        package['manifests']={str(p.relative_to(files)):verify_manifest(p) for p in sorted(files.rglob('SHA256SUMS.txt'))}
        assert 'SHA256SUMS.txt' in package['manifests']
        outer_paths={x['path'] for x in package['manifests']['SHA256SUMS.txt']}
        actual_paths={str(p.relative_to(files)) for p in files.rglob('*') if p.is_file() and p!=files/'SHA256SUMS.txt'}
        assert outer_paths==actual_paths,'Outer manifest omits artifact members'
        context=parse_context(files/'workflow-context.txt')
        assert context['source_sha']==expected
        assert context['run_id']=='36554757065'
        package['workflow_context']=context
        runs=[]
        for retained in sorted(p for p in files.glob('run.*') if p.is_dir()):
            context=parse_context(retained/'context.txt')
            assert context['source_sha']==expected
            assert context['max_workers']=='2'
            script_hash=sha(root/'source-so-determinism.sh')
            assert script_hash in (retained/'context.txt').read_text()
            libraries=[]
            for p in sorted(retained.glob('libskein_llama.*.so')):
                header=p.read_bytes()[:64]
                assert header[:6]==b'\x7fELF\x02\x01','Not ELF64 little endian'
                assert struct.unpack_from('<H',header,18)[0]==183,'Not AArch64'
                h=sha(p)
                assert h[:8] in p.name
                libraries.append({'path':str(p.relative_to(files)),'sha256':h,'bytes':p.stat().st_size,'format':'ELF64 little-endian AArch64'})
            logs=[]
            for p in sorted(retained.glob('build-*.log')):
                text=p.read_text(errors='replace')
                logs.append({'path':str(p.relative_to(files)),'sha256':sha(p),'bytes':p.stat().st_size,'build_successful':'BUILD SUCCESSFUL' in text,'build_failed':'BUILD FAILED' in text,'lines':len(text.splitlines()),'native_task_lines':[line for line in text.splitlines() if 'externalNativeBuild' in line or 'buildCMake' in line or 'configureCMake' in line]})
            run_result=parse_context(retained/'result.txt') if (retained/'result.txt').exists() else {'exit_status':'missing'}
            runs.append({'directory':retained.name,'context':context,'context_sha256':sha(retained/'context.txt'),'result':run_result,'libraries':libraries,'equal_libraries':len({p['sha256'] for p in libraries})==1 if libraries else False,'logs':logs})
        package['runs']=runs
        package['worker_flag_evidence']='Retained exact script SHA matches source; its only per-build Gradle call includes --max-workers=2. Default Gradle logs do not echo argv.'
        assert '--max-workers=2' in (root/'source-so-determinism.sh').read_text()
    elif meta['name'].startswith('rb-build-'):
        contexts=list(files.glob('context-*.txt'))
        for p in contexts:
            context=parse_context(p)
            assert context['commit']==expected
            package.setdefault('contexts',[]).append(context)
        for p in files.glob('SHA256SUMS-*.txt'):
            package.setdefault('manifests',{})[p.name]=verify_manifest(p)
        for p in files.glob('*.apk'):
            assert zipfile.is_zipfile(p)
            package['files'].append({'path':p.name,'sha256':sha(p),'bytes':p.stat().st_size})
    result['artifacts'].append(package)
if len([a for a in result['artifacts'] if a['name'].startswith('rb-build-')])==2:
    apks=[f for a in result['artifacts'] if a['name'].startswith('rb-build-') for f in a['files']]
    result['release_apks_equal']=len(apks)==2 and len({f['sha256'] for f in apks})==1
print(json.dumps(result,indent=2))

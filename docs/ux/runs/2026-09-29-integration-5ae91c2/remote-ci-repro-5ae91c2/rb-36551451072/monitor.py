import datetime, hashlib, json, pathlib, subprocess, time, zipfile
root=pathlib.Path(__file__).resolve().parent
repo='HeyZeus100/skein'
run='36551451072'
expected='5ae91c238612bdd9b1511b3716d34b4fa5960dd2'
seen=set()
joblogs=set()
previous=None

def stamp(): return datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
def api(path):
    result=subprocess.run(['gh','api',f'repos/{repo}/{path}'],capture_output=True)
    if result.returncode:
        (root/('api-failure-'+stamp()+'.log')).write_bytes(result.stderr+result.stdout)
        return None
    return json.loads(result.stdout)
def archive(artifact):
    key=str(artifact['id'])
    target=root/('artifact-'+key+'-'+artifact['name'])
    target.mkdir(exist_ok=False)
    (target/'metadata.json').write_text(json.dumps(artifact,indent=2)+'\n')
    raw=target/'archive.zip'
    with raw.open('wb') as out:
        result=subprocess.run(['gh','api',f'repos/{repo}/actions/artifacts/{key}/zip'],stdout=out,stderr=subprocess.PIPE)
    if result.returncode:
        (target/'download-error.log').write_bytes(result.stderr)
        print('ARTIFACT DOWNLOAD FAILED '+key,flush=True)
        return
    digest=hashlib.sha256(raw.read_bytes()).hexdigest()
    check={'sha256':digest,'api_digest':artifact.get('digest'),'matches_api_digest':artifact.get('digest') in (None,'sha256:'+digest)}
    (target/'archive-verification.json').write_text(json.dumps(check,indent=2)+'\n')
    extraction=target/'files'
    extraction.mkdir()
    with zipfile.ZipFile(raw) as z:
        for entry in z.infolist():
            p=(extraction/entry.filename).resolve()
            if not p.is_relative_to(extraction.resolve()): raise ValueError('unsafe zip path '+entry.filename)
        bad=z.testzip()
        if bad: raise ValueError('bad archive member '+bad)
        z.extractall(extraction)
    print('ARTIFACT SAVED '+json.dumps({'id':key,'name':artifact['name'],**check}),flush=True)

while True:
    status=api('actions/runs/'+run)
    jobs=api('actions/runs/'+run+'/jobs?per_page=100')
    artifacts=api('actions/runs/'+run+'/artifacts?per_page=100')
    if status is None or jobs is None or artifacts is None:
        time.sleep(45)
        continue
    if status['head_sha']!=expected: raise ValueError('source SHA changed')
    snapshot={'run':status,'jobs':jobs,'artifacts':artifacts}
    (root/('snapshot-'+stamp()+'.json')).write_text(json.dumps(snapshot,indent=2)+'\n')
    summary=[(j['id'],j['name'],j['status'],j['conclusion']) for j in jobs['jobs']]
    if summary!=previous:
        print('STATUS '+json.dumps(summary),flush=True)
        previous=summary
    for artifact in artifacts['artifacts']:
        if artifact['id'] not in seen:
            archive(artifact)
            seen.add(artifact['id'])
    for job in jobs['jobs']:
        if job['status']=='completed' and job['conclusion']!='skipped' and job['id'] not in joblogs:
            path=root/('job-'+str(job['id'])+'.log')
            with path.open('wb') as out:
                result=subprocess.run(['gh','api',f"repos/{repo}/actions/jobs/{job['id']}/logs"],stdout=out,stderr=subprocess.PIPE)
            if result.returncode:
                path.rename(root/('job-log-failure-'+str(job['id'])+'-'+stamp()+'.log'))
                (root/('job-log-error-'+stamp()+'.log')).write_bytes(result.stderr)
            else:
                joblogs.add(job['id'])
                print('JOB LOG SAVED '+str(job['id']),flush=True)
    if status['status']=='completed':
        print('RUN COMPLETE '+str(status['conclusion']),flush=True)
        break
    time.sleep(45)

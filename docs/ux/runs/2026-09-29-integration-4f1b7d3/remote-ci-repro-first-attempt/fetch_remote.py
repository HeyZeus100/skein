#!/usr/bin/env python3
import datetime, hashlib, json, pathlib, subprocess, sys
root = pathlib.Path(__file__).resolve().parent
run = sys.argv[1]
assert run in ('36546803273', '36546803361')
base = root / run
base.mkdir(exist_ok=True)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
repo = 'repos/HeyZeus100/skein'
def capture_json(endpoint, name):
    raw = subprocess.check_output(['gh','api',f'{repo}/{endpoint}'])
    (base / f'{stamp}-{name}.json').write_bytes(raw)
    return json.loads(raw)
record = capture_json(f'actions/runs/{run}', 'run')
assert record['head_sha'] == '4f1b7d334173544feb47190b3e2e7efc4a5e1688', record['head_sha']
jobs = capture_json(f'actions/runs/{run}/jobs?per_page=100', 'jobs')
artifacts = capture_json(f'actions/runs/{run}/artifacts?per_page=100', 'artifacts')
print(json.dumps({'run':run,'status':record['status'],'conclusion':record['conclusion'],'jobs':[{k:j[k] for k in ('id','name','status','conclusion')} for j in jobs['jobs']],'artifacts':[{k:a.get(k) for k in ('id','name','size_in_bytes','digest')} for a in artifacts['artifacts']]}, indent=2),flush=True)
for artifact in artifacts['artifacts']:
    name = artifact['name']
    assert '/' not in name and '..' not in name
    target = base / f"artifact-{artifact['id']}-{name}.zip"
    if target.exists():
        continue
    partial = base / f'{target.name}.{stamp}.partial'
    with partial.open('xb') as f:
        subprocess.run(['gh','api',f"{repo}/actions/artifacts/{artifact['id']}/zip"], stdout=f, check=True)
    actual = 'sha256:' + hashlib.sha256(partial.read_bytes()).hexdigest()
    assert artifact.get('digest') == actual, (name, artifact.get('digest'),actual)
    partial.rename(target)
    print(f'DOWNLOADED {target} {actual}',flush=True)
if record['status'] == 'completed' and not (base / 'run-logs.zip').exists():
    partial = base / f'{stamp}-run-logs.zip.partial'
    with partial.open('xb') as f:
        subprocess.run(['gh','api',f'{repo}/actions/runs/{run}/logs'],stdout=f,check=True)
    partial.rename(base / 'run-logs.zip')
    print('LOGS sha256:' + hashlib.sha256((base / 'run-logs.zip').read_bytes()).hexdigest(),flush=True)

import concurrent.futures,datetime,hashlib,json,pathlib,subprocess,zipfile
ROOT=pathlib.Path(__file__).resolve().parent
EXPECTED='2e1dcbafd645d4bb9348340905949bc5cae90acf'
REPO='HeyZeus100/skein'
LANES={'ci':36559029276,'screenshots':36559029446,'rb':36559029462}
def stamp():return datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def api(path):
 r=subprocess.run(['gh','api',f'repos/{REPO}/{path}'],capture_output=True,check=True)
 return json.loads(r.stdout)
def lane(item):
 name,run=item;root=ROOT/f'{name}-{run}'
 try:
  with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
   futures=[ex.submit(api,f'actions/runs/{run}'+tail) for tail in ['', '/jobs?per_page=100','/artifacts?per_page=100']]
   status,jobs,artifacts=[f.result() for f in futures]
  assert status['head_sha']==EXPECTED
  snapshot={'run':status,'jobs':jobs,'artifacts':artifacts}
  (root/f'snapshot-{stamp()}.json').write_text(json.dumps(snapshot,indent=2)+'\n')
  print(name,status['status'],status['conclusion'],'jobs',[(j['name'],j['status'],j['conclusion']) for j in jobs['jobs']],flush=True)
  for a in artifacts['artifacts']:
   assert a['workflow_run']['head_sha']==EXPECTED
   target=root/f"artifact-{a['id']}-{a['name']}"
   if target.exists():
    assert (target/'archive-verification.json').is_file(),'prior incomplete artifact '+str(target)
    continue
   target.mkdir()
   (target/'metadata.json').write_text(json.dumps(a,indent=2)+'\n')
   raw=target/'archive.zip'
   with raw.open('xb') as f:
    r=subprocess.run(['gh','api',f"repos/{REPO}/actions/artifacts/{a['id']}/zip"],stdout=f,stderr=subprocess.PIPE)
   if r.returncode:
    (target/'download-error.log').write_bytes(r.stderr)
    raise RuntimeError('artifact download failed '+str(a['id']))
   digest=sha(raw)
   assert a['digest']=='sha256:'+digest
   assert raw.stat().st_size==a['size_in_bytes']
   files=target/'files';files.mkdir()
   with zipfile.ZipFile(raw) as z:
    assert z.testzip() is None
    assert len(z.namelist())==len(set(z.namelist())),'duplicate ZIP names'
    for entry in z.infolist():assert (files/entry.filename).resolve().is_relative_to(files.resolve())
    z.extractall(files)
   (target/'archive-verification.json').write_text(json.dumps({'id':a['id'],'name':a['name'],'bytes':raw.stat().st_size,'sha256':digest,'api_digest':a['digest'],'size_and_digest_match':True,'source_sha':EXPECTED},indent=2)+'\n')
   print(name,'artifact verified',a['id'],a['name'],digest,flush=True)
  for j in jobs['jobs']:
   if j['status']!='completed' or j['conclusion']=='skipped':continue
   p=root/f"job-{j['id']}.log"
   if p.exists():continue
   r=subprocess.run(['gh','api',f"repos/{REPO}/actions/jobs/{j['id']}/logs"],capture_output=True)
   if r.returncode:(root/f"log-error-{j['id']}-{stamp()}.txt").write_bytes(r.stdout+r.stderr)
   else:p.write_bytes(r.stdout);print(name,'job log retained',j['id'],flush=True)
 except Exception as e:
  (root/f'poll-error-{stamp()}.txt').write_text(repr(e)+'\n')
  print(name,'ERROR',repr(e),flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
 list(ex.map(lane,LANES.items()))

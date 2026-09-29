#!/usr/bin/env python3
import collections, hashlib, io, json, pathlib, struct, sys, xml.etree.ElementTree as ET, zipfile
root = pathlib.Path(__file__).resolve().parent
source = '4f1b7d334173544feb47190b3e2e7efc4a5e1688'
sha = lambda data: hashlib.sha256(data).hexdigest()
result = {'expected_source':source,'artifacts':[]}
for run in ('36546803273','36546803361'):
 base = root / run
 for path in sorted(base.glob('artifact-*.zip')):
  outer_data = path.read_bytes()
  item = {'path':str(path), 'bytes':len(outer_data), 'sha256':sha(outer_data)}
  z = zipfile.ZipFile(io.BytesIO(outer_data))
  assert z.testzip() is None
  item['zip_entries'] = len(z.namelist())
  manifests = [n for n in z.namelist() if n.endswith('/unit-verification.json') or n == 'unit-verification.json']
  for name in manifests:
   data = z.read(name); manifest = json.loads(data)
   assert manifest['source_sha'] == source
   verified = []
   totals = collections.Counter()
   skipped = []; failures = []; critical = []
   rootsums = collections.Counter()
   for f in manifest['files']:
    raw = z.read(f['path'])
    assert sha(raw) == f['sha256'] and len(raw) == f['bytes'], f['path']
    verified.append(f['path'])
    if not f['path'].endswith('.xml'): continue
    xml = ET.fromstring(raw)
    for attr in ('tests','skipped','failures','errors'):
     rootsums[attr] += int(xml.attrib.get(attr,0))
    for case in xml.iter('testcase'):
     desc = {'path':f['path'],'class':case.get('classname'),'name':case.get('name')}
     if case.find('failure') is not None or case.find('error') is not None:
      totals['failed_or_error'] += 1
      desc['detail'] = ET.tostring(case, encoding='unicode')
      failures.append(desc)
     elif case.find('skipped') is not None:
      totals['skipped'] += 1
      desc['reason'] = ET.tostring(case.find('skipped'), encoding='unicode')
      skipped.append(desc)
     else:
      totals['passed'] += 1
     if any(c in (case.get('classname') or '') for c in ['MainActivityModelImportTest','MainActivityDraftRetentionTest','ModelServicesImportLifecycleTest','InferenceWorkerLifecycleTest']):
      desc['outcome']='failed_or_error' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
      critical.append(desc)
   actualxml = sorted(n for n in z.namelist() if '/build/test-results/' in n and n.endswith('.xml'))
   assert actualxml == sorted(f for f in verified if f.endswith('.xml')), ('unmanifested xml',set(actualxml)-set(verified))
   item['unit'] = {'manifest_path':name,'manifest_sha256':sha(data),'source_attribution':manifest['source_attribution'],'manifest_source':manifest['source_sha'],'verified_files':len(verified),'actual_cases':dict(totals),'xml_root_counts':dict(rootsums),'skips':skipped,'failures':failures,'critical_cases':critical}
  for name in z.namelist():
   if name.startswith('context-') and name.endswith('.txt'):
    context=z.read(name).decode()
    assert f'commit={source}\n' in context
    item['build_context'] = context
   if name.startswith('SHA256SUMS-'):
    item['published_inner_hashes']=z.read(name).decode()
  for name in z.namelist():
   if name.endswith('.apk'):
    data=z.read(name)
    apk={'path':name,'bytes':len(data),'sha256':sha(data)}
    az=zipfile.ZipFile(io.BytesIO(data))
    assert az.testzip() is None
    apk['native_libraries']={n:sha(az.read(n)) for n in az.namelist() if n.startswith('lib/') and n.endswith('.so')}
    eocd=data.rfind(b'PK\x05\x06');central=struct.unpack_from('<I',data,eocd+16)[0]
    apk['v1_signature_entries']=[n for n in az.namelist() if n.startswith('META-INF/') and n.endswith(('.RSA','.DSA','.EC','.SF'))]
    apk['v2_v3_magic_present']=data[central-16:central]==b'APK Sig Block 42'
    apk['native_layout']=[]
    for info in az.infolist():
     if info.filename.startswith('lib/') and info.filename.endswith('.so'):
      lib=az.read(info);off=struct.unpack_from('<Q',lib,32)[0];entsize,count=struct.unpack_from('<HH',lib,54);aligns=[]
      for i in range(count):
       header=struct.unpack_from('<IIQQQQQQ',lib,off+i*entsize)
       if header[0]==1: aligns.append(header[-1])
      n,e=struct.unpack_from('<HH',data,info.header_offset+26);zipoff=info.header_offset+30+n+e
      apk['native_layout'].append({'name':info.filename,'machine':struct.unpack_from('<H',lib,18)[0],'pt_load_alignments':aligns,'zip_data_offset':zipoff,'compression':info.compress_type})
    if 'published_inner_hashes' in item:
     assert f"{apk['sha256']}  {name}" in item['published_inner_hashes']
    item.setdefault('apks',[]).append(apk)
  result['artifacts'].append(item)
release = [a for a in result['artifacts'] if 'build_context' in a]
if len(release)==2:
 apks=[a['apks'][0] for a in release]
 result['release_comparison']={'actual_sha256_equal':apks[0]['sha256']==apks[1]['sha256'],'actual_bytes_equal':apks[0]['bytes']==apks[1]['bytes'],'sha256_a':apks[0]['sha256'],'sha256_b':apks[1]['sha256'],'native_hashes_equal':apks[0]['native_libraries']==apks[1]['native_libraries']}
 result['release_comparison']['direct_byte_equality']=zipfile.ZipFile(release[0]['path']).read(apks[0]['path'])==zipfile.ZipFile(release[1]['path']).read(apks[1]['path'])
print(json.dumps(result,indent=2))

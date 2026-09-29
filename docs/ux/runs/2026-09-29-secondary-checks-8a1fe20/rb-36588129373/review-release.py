from pathlib import Path
import json,hashlib,zipfile,re
root=Path(__file__).resolve().parent
expected='8a1fe20bd451314596e2f5ea75766f3dc41a64ef'
epoch='1790694605'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
apks=sorted(root.glob('artifact-*-rb-build-*/files/*.apk'))
assert len(apks)==2
assert apks[0].read_bytes()==apks[1].read_bytes()
contexts=[]
for p in sorted(root.glob('artifact-*-rb-build-*/files/context-*.txt')):
 c=dict(x.split('=',1) for x in p.read_text().splitlines() if '=' in x)
 assert c['commit']==expected
 assert c.get('SOURCE_DATE_EPOCH',c.get('source_date_epoch'))==epoch,c
 contexts.append(c)
with zipfile.ZipFile(apks[0]) as a,zipfile.ZipFile(apks[1]) as b:
 assert a.namelist()==b.namelist() and len(a.namelist())==len(set(a.namelist()))
 entries=[]
 for x,y in zip(a.infolist(),b.infolist()):
  assert x.__dict__==y.__dict__ if hasattr(x,'__dict__') else all(getattr(x,k)==getattr(y,k) for k in ['filename','date_time','compress_type','comment','extra','create_system','create_version','extract_version','flag_bits','volume','internal_attr','external_attr','CRC','compress_size','file_size','header_offset'])
  p,q=a.read(x),b.read(y);assert p==q
  entries.append({'name':x.filename,'bytes':len(p),'sha256':hashlib.sha256(p).hexdigest()})
 llama=next(x for x in entries if x['name']=='lib/arm64-v8a/libskein_llama.so')
 assert llama['sha256']=='2aefda01808f10bf5693ab02d0857e0169dc09550b615896939cb176c032daff'
result={'source_sha':expected,'source_date_epoch':epoch,'release_apks':[{'path':str(p.relative_to(root)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in apks],'contexts':contexts,'whole_apk_bytes_equal':True,'entry_names_order_metadata_contents_equal':True,'entry_count':len(entries),'entries':entries,'embedded_llama_matches_cold_native_pair':True,'limits':'Within-source/epoch build equality, not installed candidate attestation or cross-source APK equality.'}
(root/'release-byte-comparison.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='entries'},indent=2))

import hashlib,json,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parent
prior=root.parent.parent/"final-source-5ae91c238/rb-36551451072"
a=next(prior.glob("artifact-*-rb-build-a/files/*.apk"))
b=next(root.glob("artifact-*-rb-build-a/files/*.apk"))
sha=lambda data:hashlib.sha256(data).hexdigest()
result={"prior_source_sha":"5ae91c238612bdd9b1511b3716d34b4fa5960dd2","new_source_sha":"69dc5cf8d6ca87414cce1808e6d71bf0c43e3567","prior_apk":{"path":str(a),"bytes":a.stat().st_size,"sha256":sha(a.read_bytes())},"new_apk":{"path":str(b),"bytes":b.stat().st_size,"sha256":sha(b.read_bytes())}}
with zipfile.ZipFile(a) as za,zipfile.ZipFile(b) as zb:
    ia,ib=za.infolist(),zb.infolist()
    ma,mb={x.filename:x for x in ia},{x.filename:x for x in ib}
    result["entry_counts"]=[len(ia),len(ib)]
    result["entry_names_and_order_equal"]=[x.filename for x in ia]==[x.filename for x in ib]
    result["only_prior_names"]=sorted(ma.keys()-mb.keys())
    result["only_new_names"]=sorted(mb.keys()-ma.keys())
    content_changes=[]
    metadata_changes=[]
    fields=("date_time","compress_type","flag_bits","create_system","create_version","extract_version","external_attr","internal_attr","extra","comment","CRC","file_size","compress_size")
    for name in sorted(ma.keys()&mb.keys()):
        xa,xb=ma[name],mb[name]
        ha,hb=sha(za.read(name)),sha(zb.read(name))
        if ha!=hb:content_changes.append({"path":name,"prior_sha256":ha,"new_sha256":hb})
        changed=[f for f in fields if getattr(xa,f)!=getattr(xb,f)]
        if changed:metadata_changes.append({"path":name,"fields":changed})
    result["content_changes"]=content_changes
    result["metadata_changes"]=metadata_changes
    result["all_uncompressed_payloads_equal"]=not(content_changes or result["only_prior_names"] or result["only_new_names"])
    result["whole_apks_equal"]=result["prior_apk"]["sha256"]==result["new_apk"]["sha256"]
print(json.dumps(result,indent=2))

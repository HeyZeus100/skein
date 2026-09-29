import hashlib,json,pathlib,re
root=pathlib.Path(__file__).resolve().parent
expected="69dc5cf8d6ca87414cce1808e6d71bf0c43e3567"
result={"source_sha":expected,"runs":[]}
for lane in sorted(root.glob("*-365*")):
    snapshot_path=sorted(lane.glob("snapshot-*.json"))[-1]
    snapshot=json.loads(snapshot_path.read_text())
    run=snapshot["run"]
    assert run["head_sha"]==expected
    assert run["status"]=="completed"
    review={"run_id":run["id"],"url":run["html_url"],"attempt":run["run_attempt"],"source_sha":run["head_sha"],"branch":run["head_branch"],"event":run["event"],"status":run["status"],"conclusion":run["conclusion"],"snapshot":str(snapshot_path.relative_to(root)),"snapshot_sha256":hashlib.sha256(snapshot_path.read_bytes()).hexdigest(),"jobs":[],"artifacts":snapshot["artifacts"]["artifacts"]}
    for job in snapshot["jobs"]["jobs"]:
        j={k:job[k] for k in ("id","name","status","conclusion","started_at","completed_at")}
        j["steps"]=[{k:s[k] for k in ("name","status","conclusion")} for s in job["steps"]]
        log=lane/("job-"+str(job["id"])+".log")
        if log.exists():
            raw=log.read_bytes()
            text=raw.decode(errors="replace")
            lines=text.splitlines()
            j["log"]={"path":str(log.relative_to(root)),"bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest()}
            j["gradle_command_lines"]=[line for line in lines if "./gradlew" in line and ("Run " in line or "assembleFossRelease" in line)]
            j["build_outcomes"]=[line for line in lines if re.search("BUILD (SUCCESSFUL|FAILED)",line)]
            j["from_cache_task_lines"]=[line for line in lines if "> Task " in line and "FROM-CACHE" in line]
        review["jobs"].append(j)
    result["runs"].append(review)
(root/"final-run-review.json").write_text(json.dumps(result,indent=2)+"\n")
print(json.dumps({"source_sha":expected,"runs":[{"id":r["run_id"],"conclusion":r["conclusion"],"jobs":len(r["jobs"]),"artifacts":len(r["artifacts"])} for r in result["runs"]]},indent=2))

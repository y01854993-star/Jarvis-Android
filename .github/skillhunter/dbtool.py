#!/usr/bin/env python3
"""Small CLI used by the workflows to update the skill database (status changes, history) and regenerate summary.json."""
import argparse, json, pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import hunt  # noqa: E402


def update(data_dir, issue=None, pr=None, status=None, event=None, pr_url=None, repo_id=None):
    d = pathlib.Path(data_dir)
    db = hunt.load_json(d / "skills.json", {"skills": {}})
    hist = hunt.load_json(d / "history.json", {"runs": []})
    rec = None
    for r in db["skills"].values():
        ap = r.get("approval") or {}
        if (issue is not None and ap.get("issue") == issue) or (pr is not None and ap.get("pr") == pr) or (repo_id and r["id"] == repo_id):
            rec = r
            break
    if rec is None:
        raise SystemExit(f"no skill found for issue={issue} pr={pr}")
    if status:
        if status not in hunt.STATUSES:
            raise SystemExit(f"unknown status {status}")
        rec["status"] = status
    ap = rec.setdefault("approval", {})
    if pr is not None:
        ap["pr"] = pr
    if pr_url:
        ap["pr_url"] = pr_url
    rec["history"].append({"ts": hunt.iso(hunt.now_utc()), "event": (event or f"status -> {status}")[:200]})
    rec["history"] = rec["history"][-30:]
    (d / "skills.json").write_text(json.dumps(db, indent=1, ensure_ascii=False), encoding="utf-8")
    (d / "summary.json").write_text(json.dumps(hunt.summarize(db, hist, hunt.now_utc()), ensure_ascii=False), encoding="utf-8")
    return rec


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data")
    ap.add_argument("--issue", type=int)
    ap.add_argument("--pr", type=int)
    ap.add_argument("--status")
    ap.add_argument("--event")
    ap.add_argument("--pr-url")
    a = ap.parse_args()
    r = update(a.data, a.issue, a.pr, a.status, a.event, a.pr_url)
    print(r["repo"], r["status"])

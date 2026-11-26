#!/usr/bin/env python3
"""Scores a model on held-out steps: did it predict the right next action? (exact action + target, and action type only)."""
import argparse
import json
import re


def parse_action(text):
    """Pulls {"do": ..., "target": ...} out of a model answer, tolerating code fences and extra words."""
    m = re.search(r"\{.*\}", text or "", re.S)
    if not m:
        return None
    try:
        obj = json.loads(m.group(0))
    except json.JSONDecodeError:
        return None
    return {"do": str(obj.get("do", "")).strip().lower(), "target": str(obj.get("target", "")).strip().lower()}


def score(preds, golds):
    n = len(golds)
    if n == 0 or len(preds) != n:
        raise ValueError("need the same, non-zero number of predictions and gold answers")
    action_ok = exact_ok = parsed = 0
    for p, g in zip(preds, golds):
        pa, ga = parse_action(p), parse_action(g)
        if pa is None or ga is None:
            continue
        parsed += 1
        action_ok += pa["do"] == ga["do"]
        exact_ok += pa == ga
    return {"n": n, "parsed": parsed / n, "action_accuracy": action_ok / n, "exact_accuracy": exact_ok / n}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--predictions", required=True, help="JSON list of model answers, same order as --val")
    ap.add_argument("--val", required=True, help="val.json from prepare_dataset.py")
    a = ap.parse_args()
    preds = json.load(open(a.predictions, encoding="utf-8"))
    golds = [s["messages"][-1]["content"] for s in json.load(open(a.val, encoding="utf-8"))]
    print(json.dumps(score(preds, golds), indent=1))


if __name__ == "__main__":
    main()

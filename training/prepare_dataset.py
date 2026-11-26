#!/usr/bin/env python3
"""Turns the agent episodes exported by the JARVIS app into fine-tuning data for a vision-language model.

  python training/prepare_dataset.py --episodes episodes.jsonl --images frames/ --out data/

Output: data/train.json, data/val.json (OpenAI-style messages + images) and data/dataset_info.json (LLaMA-Factory)."""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import common  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--episodes", required=True)
    ap.add_argument("--images", default=None, help="folder with the frame JPEGs referenced by the episodes")
    ap.add_argument("--out", default="data")
    ap.add_argument("--window", type=int, default=3, help="how many previous frames the model sees")
    ap.add_argument("--val-ratio", type=float, default=0.1)
    a = ap.parse_args()
    steps = common.load_steps(a.episodes)
    samples = common.build_samples(common.group_episodes(steps), a.window, a.images)
    if not samples:
        raise SystemExit("no successful steps found: record more episodes (dataset_collect) before training")
    train, val = common.split_by_episode(samples, a.val_ratio)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "train.json").write_text(json.dumps(common.strip_private(train), ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "val.json").write_text(json.dumps(common.strip_private(val), ensure_ascii=False, indent=1), encoding="utf-8")
    info = {"jarvis_train": {"file_name": "train.json", "formatting": "sharegpt", "columns": {"messages": "messages", "images": "images"},
                             "tags": {"role_tag": "role", "content_tag": "content", "user_tag": "user", "assistant_tag": "assistant"}}}
    (out / "dataset_info.json").write_text(json.dumps(info, indent=1), encoding="utf-8")
    with_images = sum(1 for s in samples if s.get("images"))
    print(f"{len(samples)} samples ({with_images} with images) -> {len(train)} train / {len(val)} val")


if __name__ == "__main__":
    main()

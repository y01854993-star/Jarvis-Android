"""Shared, dependency-free helpers for the JARVIS vision training toolkit (no torch needed, so they are unit tested in CI)."""
import json
import pathlib
import random

IMAGE_TOKEN = "<image>"


def load_steps(path):
    """Reads the JSON Lines file exported by the app (one agent step per line)."""
    steps = []
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            steps.append(json.loads(line))
    return steps


def group_episodes(steps):
    episodes = {}
    for s in steps:
        episodes.setdefault(s["episode"], []).append(s)
    for ep in episodes.values():
        ep.sort(key=lambda s: s["index"])
    return episodes


def build_samples(episodes, window=3, images_dir=None):
    """One supervised sample per successful step: the previous `window` frames plus the current one, the goal and the previous
    actions go in; the next action comes out. Learning only from steps that worked keeps the model from copying mistakes."""
    samples = []
    for ep_id, steps in episodes.items():
        for k, st in enumerate(steps):
            if not st.get("success"):
                continue
            prev = steps[max(0, k - window):k]
            frames = [s["frame"] for s in prev + [st] if s.get("frame")]
            history = "\n".join(f"{j + 1}. {p['action']} {p.get('target', '')}".strip() for j, p in enumerate(prev)) or "(none)"
            user = IMAGE_TOKEN * len(frames) + f"GOAL: {st['goal']}\nPREVIOUS ACTIONS:\n{history}\nWhat is the next action? Answer with JSON."
            answer = json.dumps({"do": st["action"], "target": st.get("target", "")}, ensure_ascii=False)
            sample = {"_episode": ep_id, "messages": [{"role": "user", "content": user}, {"role": "assistant", "content": answer}]}
            if frames:
                sample["images"] = [str(pathlib.Path(images_dir) / f) if images_dir else f for f in frames]
            samples.append(sample)
    return samples


def split_by_episode(samples, val_ratio=0.1, seed=7):
    """Whole episodes go to either train or validation, so the model is never tested on a step it saw a neighbour of."""
    ids = sorted({s["_episode"] for s in samples})
    random.Random(seed).shuffle(ids)
    n_val = max(1, int(len(ids) * val_ratio)) if len(ids) > 1 else 0
    val_ids = set(ids[:n_val])
    train = [s for s in samples if s["_episode"] not in val_ids]
    val = [s for s in samples if s["_episode"] in val_ids]
    return train, val


def strip_private(samples):
    return [{k: v for k, v in s.items() if not k.startswith("_")} for s in samples]


def to_chat(sample):
    """Converts a sample to the chat-template structure used by Hugging Face multimodal processors: image placeholders become
    {"type": "image"} parts so that the number of images in the text always matches the images supplied."""
    content = []
    for m in sample["messages"]:
        parts = []
        text = m["content"]
        while text.startswith(IMAGE_TOKEN):
            parts.append({"type": "image"})
            text = text[len(IMAGE_TOKEN):]
        parts.append({"type": "text", "text": text})
        content.append({"role": m["role"], "content": parts})
    n_images = sum(1 for m in content for p in m["content"] if p["type"] == "image")
    if n_images != len(sample.get("images", [])):
        raise ValueError(f"{n_images} image placeholders but {len(sample.get('images', []))} images")
    return content

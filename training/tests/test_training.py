import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import common  # noqa: E402
import evaluate  # noqa: E402


def step(ep, i, action="click", target="play", ok=True, frame=True):
    return {"episode": ep, "index": i, "goal": "open youtube", "action": action, "target": target, "success": ok,
            "screen_digest": "ff", "frame": f"{ep}_{i}.jpg" if frame else None, "ts": 1000 + i}


class Samples(unittest.TestCase):
    def episodes(self):
        steps = [step("e1", i) for i in range(5)] + [step("e2", i) for i in range(4)] + [step("e3", 0, ok=False)]
        return common.group_episodes(steps)

    def test_failed_steps_are_not_learned(self):
        s = common.build_samples(self.episodes())
        self.assertEqual(len(s), 9)
        self.assertTrue(all(x["_episode"] != "e3" for x in s))

    def test_window_limits_the_frames(self):
        s = common.build_samples(self.episodes(), window=2)
        longest = max(len(x["images"]) for x in s)
        self.assertEqual(longest, 3)
        first = [x for x in s if x["_episode"] == "e1"][0]
        self.assertEqual(len(first["images"]), 1)

    def test_placeholders_match_images(self):
        for x in common.build_samples(self.episodes()):
            self.assertEqual(x["messages"][0]["content"].count(common.IMAGE_TOKEN), len(x.get("images", [])))

    def test_to_chat_builds_image_parts_and_checks_the_count(self):
        x = common.build_samples(self.episodes())[3]
        chat = common.to_chat(x)
        self.assertEqual(sum(p["type"] == "image" for p in chat[0]["content"]), len(x["images"]))
        bad = dict(x)
        bad["images"] = x["images"][:-1]
        with self.assertRaises(ValueError):
            common.to_chat(bad)

    def test_split_never_mixes_an_episode(self):
        s = common.build_samples(self.episodes())
        train, val = common.split_by_episode(s, 0.4)
        self.assertFalse({x["_episode"] for x in train} & {x["_episode"] for x in val})
        self.assertTrue(val)

    def test_private_keys_are_stripped(self):
        out = common.strip_private(common.build_samples(self.episodes()))
        self.assertTrue(all(not any(k.startswith("_") for k in x) for x in out))

    def test_images_dir_is_prefixed(self):
        s = common.build_samples(self.episodes(), images_dir="frames")
        self.assertTrue(s[0]["images"][0].startswith("frames"))

    def test_text_only_steps_still_produce_samples(self):
        eps = common.group_episodes([step("t", i, frame=False) for i in range(3)])
        s = common.build_samples(eps)
        self.assertEqual(len(s), 3)
        self.assertNotIn("images", s[0])


class Files(unittest.TestCase):
    def test_roundtrip_through_jsonl(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "e.jsonl"
            p.write_text("\n".join(json.dumps(step("e1", i)) for i in range(3)) + "\n\n", encoding="utf-8")
            self.assertEqual(len(common.load_steps(p)), 3)

    def test_prepare_script_runs_end_to_end(self):
        import subprocess
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "e.jsonl"
            rows = [step(f"e{n}", i) for n in range(5) for i in range(4)]
            p.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
            out = pathlib.Path(d) / "out"
            r = subprocess.run([sys.executable, str(HERE.parent / "prepare_dataset.py"), "--episodes", str(p), "--out", str(out)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            train = json.loads((out / "train.json").read_text(encoding="utf-8"))
            val = json.loads((out / "val.json").read_text(encoding="utf-8"))
            self.assertEqual(len(train) + len(val), 20)
            self.assertTrue((out / "dataset_info.json").exists())

    def test_prepare_refuses_empty_data(self):
        import subprocess
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "e.jsonl"
            p.write_text(json.dumps(step("e", 0, ok=False)), encoding="utf-8")
            r = subprocess.run([sys.executable, str(HERE.parent / "prepare_dataset.py"), "--episodes", str(p), "--out", d], capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)


class Scoring(unittest.TestCase):
    def test_parse_tolerates_fences_and_noise(self):
        self.assertEqual(evaluate.parse_action('```json\n{"do": "Click", "target": "Play"}\n```'), {"do": "click", "target": "play"})
        self.assertIsNone(evaluate.parse_action("no json here"))
        self.assertIsNone(evaluate.parse_action("{broken"))

    def test_scores(self):
        gold = ['{"do":"click","target":"a"}', '{"do":"type","target":"b"}', '{"do":"scroll","target":""}']
        pred = ['{"do":"click","target":"a"}', '{"do":"type","target":"x"}', "garbage"]
        r = evaluate.score(pred, gold)
        self.assertAlmostEqual(r["action_accuracy"], 2 / 3)
        self.assertAlmostEqual(r["exact_accuracy"], 1 / 3)
        self.assertAlmostEqual(r["parsed"], 2 / 3)

    def test_length_mismatch_is_an_error(self):
        with self.assertRaises(ValueError):
            evaluate.score(["x"], ["a", "b"])


if __name__ == "__main__":
    unittest.main()

import json, pathlib, sys, unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import hunt  # noqa: E402

CFG = json.loads((pathlib.Path(__file__).resolve().parents[1] / "config.json").read_text(encoding="utf-8"))
NOW = hunt.parse_iso("2026-11-23T00:00:00Z")


def meta(**kw):
    m = {"full_name": "a/b", "html_url": "https://github.com/a/b", "description": "wake word engine", "license": {"spdx_id": "MIT"},
         "stargazers_count": 500, "pushed_at": "2026-11-01T00:00:00Z", "archived": False, "fork": False, "open_issues_count": 10, "language": "Kotlin"}
    m.update(kw)
    return m


def info(**kw):
    i = {"readme": "a fine library", "scripts": {}, "manifest_perms": [], "min_sdk": 24, "android_native": True, "release": {"tag": "v1"}}
    i.update(kw)
    return i


class Licenses(unittest.TestCase):
    def test_classes(self):
        self.assertEqual(hunt.classify_license("MIT", CFG), "allowed")
        self.assertEqual(hunt.classify_license("Apache-2.0", CFG), "allowed")
        self.assertEqual(hunt.classify_license("GPL-3.0", CFG), "copyleft")
        self.assertEqual(hunt.classify_license("NOASSERTION", CFG), "unclear")
        self.assertEqual(hunt.classify_license(None, CFG), "unclear")


class Parsing(unittest.TestCase):
    def test_install_script(self):
        self.assertTrue(hunt.scan_install_script("curl -s http://x.sh | sh"))
        self.assertTrue(hunt.scan_install_script("echo aGk= | base64 -d | bash"))
        self.assertEqual(hunt.scan_install_script("echo hello\nmake build"), [])

    def test_manifest_and_sdk(self):
        xml = '<uses-permission android:name="android.permission.READ_SMS"/><uses-permission android:name="android.permission.INTERNET"/>'
        self.assertEqual(hunt.parse_permissions(xml), ["INTERNET", "READ_SMS"])
        self.assertEqual(hunt.parse_min_sdk("defaultConfig { minSdk = 29 }"), 29)
        self.assertEqual(hunt.parse_min_sdk("minSdkVersion 21"), 21)
        self.assertIsNone(hunt.parse_min_sdk("nothing"))

    def test_query_rotation(self):
        a = hunt.select_queries(CFG, "light", 1)
        b = hunt.select_queries(CFG, "light", 2)
        self.assertEqual(len(a), CFG["light_queries_per_run"])
        self.assertNotEqual(a, b)
        self.assertLessEqual(len(hunt.select_queries(CFG, "deep", 1)), CFG["deep_queries_per_run"])


class Review(unittest.TestCase):
    def rv(self, m=None, i=None):
        return hunt.heuristic_review(m or meta(), i or info(), CFG, NOW, 26)

    def test_clean_project_passes(self):
        r = self.rv()
        self.assertEqual(r["reject"], [])
        self.assertEqual(r["license_class"], "allowed")

    def test_rejections(self):
        self.assertTrue(self.rv(meta(archived=True))["reject"])
        self.assertTrue(self.rv(meta(fork=True))["reject"])
        self.assertTrue(self.rv(meta(license=None))["reject"])
        self.assertTrue(self.rv(meta(license={"spdx_id": "GPL-3.0"}))["reject"])
        self.assertTrue(self.rv(meta(pushed_at="2024-01-01T00:00:00Z"))["reject"])
        self.assertTrue(self.rv(meta(stargazers_count=3))["reject"])
        self.assertTrue(self.rv(i=info(min_sdk=33))["reject"])
        self.assertTrue(self.rv(m=meta(description="a keylogger"))["reject"])
        self.assertTrue(self.rv(i=info(scripts={"install.sh": "curl x | sh"}))["reject"])

    def test_flags_do_not_reject(self):
        r = self.rv(i=info(manifest_perms=["READ_SMS"], android_native=False, release=None))
        self.assertEqual(r["reject"], [])
        self.assertGreaterEqual(len(r["flags"]), 2)


class Decision(unittest.TestCase):
    ok = {"reject": [], "flags": [], "license_class": "allowed"}
    good = {"already_have": False, "better_than_existing": False, "integration_possible": True, "security_risk": "low", "score": 85}

    def test_no_ai_is_never_recommended(self):
        self.assertEqual(hunt.decide_status(self.ok, None, CFG)[0], "UNDER_REVIEW")

    def test_reject_wins_over_ai(self):
        self.assertEqual(hunt.decide_status({"reject": ["x"], "flags": []}, self.good, CFG)[0], "REJECTED")

    def test_ai_rules(self):
        self.assertEqual(hunt.decide_status(self.ok, self.good, CFG)[0], "RECOMMENDED")
        self.assertEqual(hunt.decide_status(self.ok, {**self.good, "score": 40}, CFG)[0], "DISCOVERED")
        self.assertEqual(hunt.decide_status(self.ok, {**self.good, "already_have": True}, CFG)[0], "REJECTED")
        self.assertEqual(hunt.decide_status(self.ok, {**self.good, "already_have": True, "better_than_existing": True}, CFG)[0], "RECOMMENDED")
        self.assertEqual(hunt.decide_status(self.ok, {**self.good, "security_risk": "high"}, CFG)[0], "REJECTED")
        self.assertEqual(hunt.decide_status(self.ok, {**self.good, "integration_possible": False}, CFG)[0], "REJECTED")

    def test_dedup(self):
        self.assertTrue(hunt.needs_reevaluation(None, "v1"))
        self.assertFalse(hunt.needs_reevaluation({"latest_release": "v1", "status": "REJECTED"}, "v1"))
        self.assertTrue(hunt.needs_reevaluation({"latest_release": "v1", "status": "REJECTED"}, "v2"))
        self.assertTrue(hunt.needs_reevaluation({"latest_release": "v1", "status": "UNDER_REVIEW"}, "v1"))
        self.assertFalse(hunt.needs_reevaluation({"latest_release": "v1", "status": "AWAITING_APPROVAL"}, "v1"))


class Records(unittest.TestCase):
    def make(self, status="RECOMMENDED", ai=None):
        rv = hunt.heuristic_review(meta(), info(), CFG, NOW, 26)
        return hunt.build_record(meta(), info(), rv, ai, status, "why", "voice", NOW, None)

    def test_record_fields(self):
        r = self.make()
        for k in ("id", "repo", "url", "license", "security", "compatibility", "status", "history", "discovered_at"):
            self.assertIn(k, r)
        self.assertIn(r["status"], hunt.STATUSES)

    def test_issue_body_has_approval_commands(self):
        ai = {"score": 88, "what_it_is": "x", "reasoning": "y", "files_to_modify": ["app/src/main/java/com/jarvis/tablet/Engine.kt"]}
        body = hunt.issue_body(self.make(ai=ai))
        for cmd in ("/approve", "/reject", "/test", "/info"):
            self.assertIn(cmd, body)
        self.assertIn("Nothing is merged", body)

    def test_summary_counts(self):
        db = {"skills": {"a/b": self.make(), "c/d": self.make("REJECTED")}}
        s = hunt.summarize(db, {"runs": []}, NOW)
        self.assertEqual(s["total"], 2)
        self.assertEqual(s["counts"]["REJECTED"], 1)
        self.assertEqual(len(s["pending"]), 1)


if __name__ == "__main__":
    unittest.main()

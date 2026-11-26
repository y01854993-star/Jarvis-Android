import pathlib, sys, tempfile, unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import integrate  # noqa: E402


class Rules(unittest.TestCase):
    def test_dependency_coordinates(self):
        self.assertTrue(integrate.valid_dep("com.squareup.okhttp3:okhttp:4.12.0"))
        self.assertFalse(integrate.valid_dep("https://evil.example/a.jar"))
        self.assertFalse(integrate.valid_dep("a:b"))
        self.assertFalse(integrate.valid_dep('x:y:1"); exec("rm'))

    def test_permissions(self):
        self.assertTrue(integrate.valid_perm("android.permission.RECORD_AUDIO"))
        self.assertFalse(integrate.valid_perm("RECORD_AUDIO"))
        self.assertFalse(integrate.valid_perm("android.permission.X\"/><evil"))

    def test_paths(self):
        self.assertEqual(integrate.path_ok("app/src/main/java/com/jarvis/tablet/A.kt", True), "app/src/main/java/com/jarvis/tablet/A.kt")
        self.assertIsNone(integrate.path_ok("../../etc/passwd.kt", True))
        self.assertIsNone(integrate.path_ok(".github/workflows/x.yml", True))
        self.assertIsNone(integrate.path_ok("app/build.gradle.kts", False))
        self.assertEqual(integrate.path_ok("app/src/main/java/com/jarvis/tablet/Engine.kt", False), "app/src/main/java/com/jarvis/tablet/Engine.kt")

    def test_security_scan(self):
        b, _ = integrate.scan_added("val p = ProcessBuilder(cmd)")
        self.assertTrue(b)
        b, _ = integrate.scan_added("val l = DexClassLoader(x)")
        self.assertTrue(b)
        b, w = integrate.scan_added('val u = "http://x"')
        self.assertFalse(b)
        self.assertTrue(w)


class Apply(unittest.TestCase):
    def setUp(self):
        self.t = pathlib.Path(tempfile.mkdtemp())
        (self.t / "app/src/main/java/com/jarvis/tablet").mkdir(parents=True)
        (self.t / "app/src/main/java/com/jarvis/tablet/Engine.kt").write_text("fun a() {}\nfun b() {}\n", encoding="utf-8")
        (self.t / "app/build.gradle.kts").write_text("plugins {}\ndependencies {\n    implementation(\"x:y:1\")\n}\n", encoding="utf-8")
        (self.t / "app/src/main/AndroidManifest.xml").write_text("<manifest>\n<application/>\n</manifest>\n", encoding="utf-8")

    def test_good_plan_is_applied(self):
        plan = {"new_files": [{"path": "app/src/main/java/com/jarvis/tablet/Skill.kt", "content": "class Skill"}],
                "edits": [{"path": "app/src/main/java/com/jarvis/tablet/Engine.kt", "old": "fun a() {}", "new": "fun a() { Skill() }"}],
                "dependencies": ["org.example:lib:1.0.0"], "permissions": ["android.permission.CAMERA"]}
        changed, deps, perms, blocked, _, _ = integrate.apply_changes(self.t, plan)
        self.assertEqual(blocked, [])
        self.assertIn("app/src/main/java/com/jarvis/tablet/Skill.kt", changed)
        self.assertIn('implementation("org.example:lib:1.0.0")', (self.t / "app/build.gradle.kts").read_text(encoding="utf-8"))
        self.assertIn("android.permission.CAMERA", (self.t / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"))

    def test_dangerous_code_is_refused(self):
        plan = {"new_files": [{"path": "app/src/main/java/com/jarvis/tablet/Bad.kt", "content": "ProcessBuilder(\"sh\").start()"}]}
        changed, _, _, blocked, _, _ = integrate.apply_changes(self.t, plan)
        self.assertTrue(blocked)
        self.assertNotIn("app/src/main/java/com/jarvis/tablet/Bad.kt", changed)
        self.assertFalse((self.t / "app/src/main/java/com/jarvis/tablet/Bad.kt").exists())

    def test_non_unique_edit_and_bad_dep_are_skipped(self):
        (self.t / "app/src/main/java/com/jarvis/tablet/Engine.kt").write_text("x\nx\n", encoding="utf-8")
        plan = {"edits": [{"path": "app/src/main/java/com/jarvis/tablet/Engine.kt", "old": "x", "new": "y"}], "dependencies": ["not a coordinate"]}
        changed, deps, _, _, _, skipped = integrate.apply_changes(self.t, plan)
        self.assertEqual(changed, [])
        self.assertEqual(deps, [])
        self.assertTrue(skipped)

    def test_existing_file_is_never_overwritten_as_new(self):
        plan = {"new_files": [{"path": "app/src/main/java/com/jarvis/tablet/Engine.kt", "content": "class X"}]}
        changed, _, _, _, _, skipped = integrate.apply_changes(self.t, plan)
        self.assertEqual(changed, [])
        self.assertTrue(skipped)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Run with: python3 config/quality/test-verify-baseline-profile.py"""

import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


checker_path = Path(__file__).with_name("verify-baseline-profile.py")
spec = importlib.util.spec_from_file_location("verify_baseline_profile", checker_path)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class BaselineProfileVerifierTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.apk = root / "app.apk"
        self.profile = root / "baseline-prof.txt"
        self.profile.write_text("Lsample/Live;\n", encoding="utf-8")

    def make_apk(self, dex=None):
        with zipfile.ZipFile(self.apk, "w") as apk:
            apk.writestr("classes.dex" if dex is not None else "other.txt", dex or b"")

    def verify(self):
        output = io.StringIO()
        args = ["verify-baseline-profile.py", "--apk", str(self.apk), "--profile", str(self.profile)]
        with patch.object(sys, "argv", args), contextlib.redirect_stdout(output):
            result = checker.main()
        return result, output.getvalue()

    def test_rejects_stale_rule(self):
        self.make_apk(b"dex\n")
        self.profile.write_text("Lsample/Live;\nHSPLsample/Missing;->go()V\n", encoding="utf-8")
        with patch.object(checker, "dex_definitions", return_value=({"Lsample/Live;"}, set())):
            result, output = self.verify()
        self.assertEqual(result, 1)
        self.assertIn("1 live, 1 stale", output)
        self.assertIn("Stale: HSPLsample/Missing;->go()V", output)

    def test_rejects_apk_without_dex(self):
        self.make_apk()
        with self.assertRaisesRegex(ValueError, "No DEX files"):
            self.verify()

    def test_rejects_non_dex_classes_entry(self):
        self.make_apk(b"not a DEX")
        with self.assertRaisesRegex(ValueError, "non-DEX classes entry"):
            self.verify()


if __name__ == "__main__":
    unittest.main()

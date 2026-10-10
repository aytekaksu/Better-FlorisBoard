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

# Independent C/M protocol fixture: one startup class, two HSP methods and one P-only method.
HEADER = "# wildcard-coverage-v1 classes=1 methods=3 sha256=8ae20dd82a0da9dc9760ffa3e049303dac4fb9bf7284754b27e9125926909e1e\n"
SOURCE = HEADER + "Lsample/Live;\nHSPLsample/Live;->**(**)**\nPLsample/Post;->**(**)**\n"
EXPANDED = (
    "Lsample/Live;\nHSPLsample/Live;->go()V\nHSPLsample/Live;->other(I)V\n"
    "PLsample/Post;->finish()V\nHSPLignored/Other;->skip()V\n"
)


class BaselineProfileVerifierTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.apk = root / "app.apk"
        self.profile = root / "baseline-prof.txt"
        self.expanded = root / "expanded.txt"
        self.profile.write_text("Lsample/Live;\n", encoding="utf-8")

    def make_apk(self, dex=None):
        with zipfile.ZipFile(self.apk, "w") as apk:
            apk.writestr("classes.dex" if dex is not None else "other.txt", dex or b"")

    def verify(self, *extra):
        output = io.StringIO()
        args = ["verify-baseline-profile.py", "--apk", str(self.apk), "--profile", str(self.profile), *extra]
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

    def test_preserves_compact_coverage_and_literal_pruning(self):
        self.make_apk(b"dex\n")
        self.profile.write_text(SOURCE, encoding="utf-8")
        equivalent = EXPANDED.replace("HSPLsample/Live;->go()V", "HSLsample/Live;->go()V\nSPLsample/Live;->go()V")
        self.expanded.write_text("\n".join(reversed(equivalent.splitlines())) + "\n", encoding="utf-8")
        args = ("--expanded-profile", str(self.expanded))
        with patch.object(checker, "dex_definitions", return_value=({"Lsample/Live;", "Lsample/Post;"}, set())):
            self.assertEqual(self.verify(*args)[0], 0)
            self.profile.write_text(SOURCE + "Lsample/Missing;\n", encoding="utf-8")
            result, output = self.verify(*args)
            self.assertEqual(result, 1)
            self.assertIn("4 live, 1 stale", output)
            pruned = self.profile.with_name("pruned.txt")
            self.assertEqual(self.verify(*args, "--pruned-output", str(pruned))[0], 0)
            self.assertEqual(pruned.read_text(encoding="utf-8"), SOURCE)

    def test_rejects_invalid_compact_coverage(self):
        self.make_apk(b"dex\n")
        cases = [
            (SOURCE, None, "require --expanded-profile"),
            (SOURCE.removeprefix(HEADER), EXPANDED, "Exactly one"),
            (HEADER + SOURCE, EXPANDED, "Exactly one"),
            (SOURCE.replace("classes=1", "classes=01"), EXPANDED, "Malformed.*header"),
            (HEADER + "Lsample/Live;\n", EXPANDED, "header without selectors"),
            (SOURCE + "HSPLsample/Live;->**(**)**\n", EXPANDED, "Duplicate wildcard owner"),
            (SOURCE.replace("HSPLsample/Live;", "SHPLsample/Live;"), EXPANDED, "Unsupported wildcard"),
            (SOURCE.replace("HSPLsample/Live;", "Lsample/Live;"), EXPANDED, "Unsupported wildcard"),
            (SOURCE.replace("HSPLsample/Live;", "HSPLsample/*;"), EXPANDED, "Unsupported wildcard"),
            (SOURCE.replace("->**(**)**", "->go(*)V", 1), EXPANDED, "Unsupported wildcard"),
            (SOURCE + "PLsample/Empty;->**(**)**\n", EXPANDED, "no expanded methods"),
            (SOURCE, EXPANDED.replace("HSPLsample/Live;->go()V\n", ""), "coverage changed"),
            (SOURCE, EXPANDED + "HSPLsample/Live;->new()V\n", "coverage changed"),
            (SOURCE, EXPANDED.replace("HSPLsample/Live;->go", "SPLsample/Live;->go"), "coverage changed"),
            (SOURCE, EXPANDED.replace("PLsample/Post;->finish", "HSPLsample/Post;->finish"), "coverage changed"),
            (SOURCE, EXPANDED.removeprefix("Lsample/Live;\n"), "coverage changed"),
            (SOURCE, EXPANDED + "Lsample/Post;\n", "coverage changed"),
            (SOURCE.replace("PLsample/Post;->**(**)**\n", ""), EXPANDED, "coverage changed"),
            (SOURCE, EXPANDED.replace("HSPLsample/Live;->go()V", "HSPLsample/Live;->**(**)**"), "Malformed expanded"),
        ]
        for source, expanded, error in cases:
            with self.subTest(error=error, source=source, expanded=expanded):
                self.profile.write_text(source, encoding="utf-8")
                args = ()
                if expanded is not None:
                    self.expanded.write_text(expanded, encoding="utf-8")
                    args = ("--expanded-profile", str(self.expanded))
                with self.assertRaisesRegex(ValueError, error):
                    self.verify(*args)
        self.profile.write_text(SOURCE, encoding="utf-8")
        self.expanded.write_text(EXPANDED, encoding="utf-8")
        with patch.object(checker, "dex_definitions", return_value=({"Lsample/Live;"}, set())):
            with self.assertRaisesRegex(ValueError, "Wildcard owner missing from APKs"):
                self.verify("--expanded-profile", str(self.expanded))


if __name__ == "__main__":
    unittest.main()

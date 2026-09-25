#!/usr/bin/env python3
"""Run with: python3 .github/scripts/test_validate_translation_deletions.py"""

import subprocess
import tempfile
import unittest
from pathlib import Path

import validate_translation_deletions as policy


class TranslationDeletionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Policy test")
        self.git("config", "user.email", "policy@example.invalid")
        self.locale = "app/src/main/res/values-fr/strings.xml"
        self.english_ids = policy.ALLOWLIST - {("string", "quick_action__toggle_autocorrect__tooltip")}
        self.source_before = self.xml(self.english_ids)
        self.locale_before = self.xml(policy.ALLOWLIST)
        self.source_after = self.xml(())
        self.locale_after = self.xml(())
        self.write(policy.SOURCE, self.source_before)
        self.write(self.locale, self.locale_before)
        self.workflow = ".github/workflows/validate-strings-no-translations.yml"
        self.write(self.workflow, "name: trusted policy\n")
        self.base = self.commit()

    def git(self, *args):
        result = subprocess.run(["git", "-C", str(self.repo), *args], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "Synthetic resource fixture")
        return self.git("rev-parse", "HEAD")

    @staticmethod
    def xml(identities):
        elements = []
        for kind, name in sorted(identities):
            if kind == "plurals":
                elements.append(f'    <plurals name="{name}"><item quantity="one">Old</item></plurals>')
            else:
                elements.append(f'    <string name="{name}">Old</string>')
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">\n'
            '    <!-- Preserve this comment -->\n'
            '    <string name="keep" comment="context">Keep &amp; retain</string>\n'
            '    <string name="other">Second</string>\n'
            + "\n".join(elements) + "\n</resources>\n"
        )

    def event(self, head, *, author=policy.MAINTAINER, head_repo=policy.REPOSITORY, body="Refs #131"):
        return {
            "repository": {"full_name": policy.REPOSITORY},
            "pull_request": {
                "base": {"sha": self.base, "ref": "main", "repo": {"full_name": policy.REPOSITORY}},
                "head": {"sha": head, "repo": {"full_name": head_repo}},
                "user": {"login": author},
                "body": body,
            },
        }

    def good_head(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after)
        return self.commit()

    def assert_rejected(self, head, event=None, state="open"):
        with self.assertRaises(policy.PolicyError):
            policy.validate(self.repo, self.base, head, event or self.event(head), state)

    def test_complete_allowlisted_deletion(self):
        head = self.good_head()
        result = policy.validate(self.repo, self.base, head, self.event(head), "open")
        self.assertIn("all 63 allowlisted keys", result)
        self.assertIn("125 declarations", result)

    def test_english_only_change_keeps_ordinary_policy(self):
        self.write(policy.SOURCE, self.source_before.replace("Keep &amp; retain", "Changed"))
        head = self.commit()
        self.assertIn("No localized", policy.validate(self.repo, self.base, head, self.event(head), "closed"))

    def test_author_issue_and_fork_claims_cannot_grant_exception(self):
        head = self.good_head()
        self.assert_rejected(head, self.event(head, author="contributor"))
        self.assert_rejected(head, self.event(head, head_repo="contributor/fork"))
        self.assert_rejected(head, self.event(head, body="Refs #1310"))
        self.assert_rejected(head, self.event(head, body="No issue link"))
        self.assert_rejected(head, state="closed")
        stale_event = self.event(head)
        stale_event["pull_request"]["head"]["sha"] = self.base
        self.assert_rejected(head, stale_event)

    def test_changed_surviving_translation(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after.replace("Keep &amp; retain", "Changed"))
        self.assert_rejected(self.commit())

    def test_new_or_renamed_key(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after.replace("</resources>", '<string name="renamed">Old</string></resources>'))
        self.assert_rejected(self.commit())

    def test_partial_deletion(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.xml({("string", "key_popup__threedots_alt")}))
        self.assert_rejected(self.commit())

    def test_reordered_survivors(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after.replace(
            '    <string name="keep" comment="context">Keep &amp; retain</string>\n'
            '    <string name="other">Second</string>',
            '    <string name="other">Second</string>\n'
            '    <string name="keep" comment="context">Keep &amp; retain</string>',
        ))
        self.assert_rejected(self.commit())

    def test_changed_comment(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after.replace("Preserve this comment", "Edited comment"))
        self.assert_rejected(self.commit())

    def test_changed_root_metadata(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after.replace('tools:ignore="MissingTranslation"', 'tools:ignore="UnusedResources"'))
        self.assert_rejected(self.commit())

    def test_mixed_code_or_baseline_change(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after)
        self.write("app/lint-baseline.xml", "<issues/>")
        self.assert_rejected(self.commit())

    def test_altered_workflow_is_not_part_of_resource_exception(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after)
        self.write(self.workflow, "name: altered policy\n")
        self.assert_rejected(self.commit())

    def test_added_or_deleted_locale_file(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after)
        self.write("app/src/main/res/values-de/strings.xml", self.locale_after)
        self.assert_rejected(self.commit())

    def test_deleted_locale_file(self):
        self.write(policy.SOURCE, self.source_after)
        (self.repo / self.locale).unlink()
        self.assert_rejected(self.commit())

    def test_renamed_locale_file(self):
        self.write(policy.SOURCE, self.source_after)
        (self.repo / "app/src/main/res/values-de").mkdir(parents=True)
        (self.repo / self.locale).rename(self.repo / "app/src/main/res/values-de/strings.xml")
        self.assert_rejected(self.commit())

    def test_changed_file_mode(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after)
        (self.repo / self.locale).chmod(0o755)
        self.assert_rejected(self.commit())

    def test_malformed_xml(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, "<resources><string>")
        self.assert_rejected(self.commit())

    def test_doctype_xml(self):
        self.write(policy.SOURCE, self.source_after)
        self.write(self.locale, self.locale_after.replace("<resources", "<!DOCTYPE resources><resources"))
        self.assert_rejected(self.commit())

    def test_insignificant_indentation_can_change(self):
        self.write(policy.SOURCE, self.source_after.replace("    <string", "  <string"))
        self.write(self.locale, self.locale_after.replace("    <string", "  <string"))
        head = self.commit()
        self.assertIn("Validated deletion", policy.validate(self.repo, self.base, head, self.event(head), "open"))


if __name__ == "__main__":
    unittest.main()

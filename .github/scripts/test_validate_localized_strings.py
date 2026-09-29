"""Contract checks for the localized-string deletion guard."""

import unittest

from validate_localized_strings import InvalidLocalizedEdit, string_names, validate_deletions


BASE = b'<resources>\n    <string name="keep">Keep</string>\n    <string name="old">Old</string>\n</resources>\n'
HEAD = b'<resources>\n    <string name="keep">Keep</string>\n</resources>\n'


class ValidateLocalizedStringsTest(unittest.TestCase):
    def test_accepts_only_a_whole_retired_string(self):
        self.assertEqual({"old"}, validate_deletions(BASE, HEAD, {"old"}))
        base = (
            b'<resources xmlns:tools="http://schemas.android.com/tools">\n'
            b'    <string name="old" tools:ignore="UnusedResources">Old</string>\n'
            b'</resources>\n'
        )
        head = b'<resources xmlns:tools="http://schemas.android.com/tools">\n</resources>\n'
        self.assertEqual({"old"}, validate_deletions(base, head, {"old"}))

    def test_rejects_translation_edits_and_unrelated_deletions(self):
        old_line = b'    <string name="old">Old</string>\n'
        still_english = BASE.replace(old_line, b'    <item type="string" name="old">Old</item>\n')
        invalid = (
            (BASE, HEAD, set()),
            (BASE, HEAD, string_names(BASE) - string_names(still_english)),
            (BASE, HEAD.replace(b"Keep", b"Changed"), {"old"}),
            (BASE, HEAD.replace(b"</resources>", b'    <string name="new">New</string>\n</resources>'), {"old"}),
            (BASE, b'<resources>\n    <string name="old">Old</string>\n</resources>\n', {"old"}),
            (BASE.replace(b'    <string name="old">', b'    <!-- note --><string name="old">'), HEAD, {"old"}),
            (BASE.replace(old_line, old_line + b'    <!-- note -->\n'), HEAD, {"old"}),
        )
        for base, head, removed_english in invalid:
            with self.subTest(head=head):
                with self.assertRaises(InvalidLocalizedEdit):
                    validate_deletions(base, head, removed_english)


if __name__ == "__main__":
    unittest.main()

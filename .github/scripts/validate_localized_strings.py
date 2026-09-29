#!/usr/bin/env python3
"""Permit only deletion of localized strings removed from English in the same PR."""

from io import BytesIO
import re
import subprocess
import sys
from xml.etree import ElementTree
from xml.sax.saxutils import quoteattr


ENGLISH = "app/src/main/res/values/strings.xml"
LOCALE_PATH = re.compile(r"app/src/main/res/values-[^/]+/strings[.]xml")
SHA = re.compile(r"[0-9a-f]{40}")
MAX_XML_BYTES = 2_000_000


class InvalidLocalizedEdit(ValueError):
    pass


def string_names(xml):
    if len(xml) > MAX_XML_BYTES:
        raise InvalidLocalizedEdit("Resource file is too large to validate")
    try:
        root = ElementTree.fromstring(xml)
    except ElementTree.ParseError as error:
        raise InvalidLocalizedEdit("Resource XML is invalid") from error
    if root.tag != "resources":
        raise InvalidLocalizedEdit("Resource XML needs a resources root")
    names = [
        element.get("name") for element in root
        if element.tag == "string" or (element.tag == "item" and element.get("type") == "string")
    ]
    if None in names or len(names) != len(set(names)):
        raise InvalidLocalizedEdit("Resource string names must be unique")
    return set(names)


def root_namespace_attrs(xml):
    attrs = []
    for event, value in ElementTree.iterparse(BytesIO(xml), events=("start-ns", "start")):
        if event == "start":
            break
        prefix, uri = value
        attrs.append("xmlns{}={}".format(":" + prefix if prefix else "", quoteattr(uri)))
    return (" " + " ".join(attrs) if attrs else "").encode("utf-8")


def validate_deletions(base_xml, head_xml, removed_english):
    """Return removed names only when the new locale is the old one minus whole string lines."""
    base_names = string_names(base_xml)
    head_names = string_names(head_xml)
    base_lines = base_xml.splitlines(keepends=True)
    head_lines = head_xml.splitlines(keepends=True)
    line_context = b"<resources" + root_namespace_attrs(base_xml) + b">"
    removed = set()
    head_index = 0
    for line in base_lines:
        if head_index < len(head_lines) and line == head_lines[head_index]:
            head_index += 1
            continue
        stripped = line.strip()
        if not stripped.startswith(b"<string") or not (
            stripped.endswith(b"</string>") or stripped.endswith(b"/>")
        ):
            raise InvalidLocalizedEdit("Only complete single-line strings may be deleted")
        try:
            entry = ElementTree.fromstring(line_context + stripped + b"</resources>")[0]
        except ElementTree.ParseError as error:
            raise InvalidLocalizedEdit("Only complete single-line strings may be deleted") from error
        if entry.tag != "string" or not entry.get("name"):
            raise InvalidLocalizedEdit("Deleted entry must be a named string")
        name = entry.get("name")
        if name not in removed_english or name in removed:
            raise InvalidLocalizedEdit("Deleted key was not removed from English")
        removed.add(name)
    if head_index != len(head_lines):
        raise InvalidLocalizedEdit("Localized strings were added, reordered, or rewritten")
    if not removed or removed != base_names - head_names or not head_names <= base_names:
        raise InvalidLocalizedEdit("Locale key inventory does not match line deletions")
    return removed


def git_output(*args):
    try:
        return subprocess.check_output(("git",) + args, stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError as error:
        raise InvalidLocalizedEdit("Could not read a changed resource from Git") from error


def validate_git_changes(base_sha, head_sha):
    if not SHA.fullmatch(base_sha) or not SHA.fullmatch(head_sha):
        raise InvalidLocalizedEdit("Expected exact Git commit IDs")
    changed = git_output(
        "diff", "--name-only", "--no-renames", "-z", base_sha, head_sha, "--",
    ).split(bytes([0]))
    locales = []
    for raw in changed:
        if raw:
            path = raw.decode("utf-8")
            if LOCALE_PATH.fullmatch(path):
                locales.append(path)
    if not locales:
        return 0
    base_english = string_names(git_output("show", "{}:{}".format(base_sha, ENGLISH)))
    head_english = string_names(git_output("show", "{}:{}".format(head_sha, ENGLISH)))
    removed_english = base_english - head_english
    total = 0
    for path in locales:
        try:
            removed = validate_deletions(
                git_output("show", "{}:{}".format(base_sha, path)),
                git_output("show", "{}:{}".format(head_sha, path)),
                removed_english,
            )
        except InvalidLocalizedEdit as error:
            raise InvalidLocalizedEdit("{}: {}".format(path, error)) from error
        total += len(removed)
    return total


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise InvalidLocalizedEdit("Expected base and head commit IDs")
        count = validate_git_changes(sys.argv[1], sys.argv[2])
    except InvalidLocalizedEdit as error:
        print("::error::{}".format(error), file=sys.stderr)
        sys.exit(1)
    print("Localized strings policy passed ({} key deletions).".format(count))

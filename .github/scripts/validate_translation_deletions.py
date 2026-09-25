#!/usr/bin/env python3
"""Allow only the issue #131 one-time removal of lint-unused translations."""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from xml.dom import Node, minidom


REPOSITORY = "aytekaksu/Better-FlorisBoard"
MAINTAINER = "aytekaksu"
ISSUE = 131
SOURCE = "app/src/main/res/values/strings.xml"
LOCALIZED = re.compile(r"app/src/main/res/values-[^/]+/strings\.xml\Z")
ISSUE_LINK = re.compile(
    r"\b(?:refs?|closes?|fixes?)\s+(?:aytekaksu/Better-FlorisBoard)?#131(?!\d)", re.I
)
MAX_XML_BYTES = 2 * 1024 * 1024

# Frozen at main 832d83c5 from the 63 distinct UnusedResources lint findings.
# The old autocorrect tooltip exists only in localized files.
ALLOWLIST = frozenset({
    ("string", name) for name in (
        "key_popup__threedots_alt",
        "one_handed__close_btn_content_description",
        "one_handed__move_start_btn_content_description",
        "one_handed__move_end_btn_content_description",
        "media__tab__emojis",
        "media__tab__emoticons",
        "media__tab__kaomoji",
        "emoji__category__smileys_emotion",
        "emoji__category__people_body",
        "emoji__category__animals_nature",
        "emoji__category__food_drink",
        "emoji__category__travel_places",
        "emoji__category__activities",
        "emoji__category__objects",
        "emoji__category__symbols",
        "emoji__category__flags",
        "quick_action__toggle_autocorrect__tooltip",
        "settings__help",
        "settings__default",
        "settings__localization__subtype_error_layout_not_installed",
        "settings__localization__group_layouts__label",
        "settings__theme_manager__title_manage",
        "pref__theme__source_assets",
        "pref__theme__source_internal",
        "pref__theme__source_external",
        "settings__theme_editor__rule_groups",
        "settings__theme_editor__property_value_color_dialog_title",
        "settings__theme_editor__component_meta_is_borderless",
        "about__app_icon_content_description",
        "about__view_licenses",
        "about__view_privacy_policy",
        "about__view_source_code",
        "about__license__title",
        "about__project_license__error_reason_asset_manager_null",
        "backup_and_restore__back_up__files_ime_spelling",
        "clip__clear_history",
        "clip__back_to_text_input",
        "clip__cant_paste",
        "clipboard__cleared_full_history",
        "ext__meta__components",
        "ext__editor__edit_component__title",
        "ext__import__file_skip_ext_incorrect_type",
        "ext__import__file_skip_ext_not_supported",
        "ext__import__file_skip_media_not_supported",
        "ext__home__manage_extensions",
        "action__export_file",
        "action__export_files",
        "action__import_files",
        "action__done",
        "action__select",
        "action__select_dir",
        "action__select_dirs",
        "error__details",
        "error__invalid",
        "state__enabled",
        "state__no_dir_selected",
        "state__no_dirs_selected",
        "enum__emoji_hair_style__default",
        "enum__emoji_hair_style__red_hair",
        "enum__emoji_hair_style__curly_hair",
        "enum__emoji_hair_style__white_hair",
        "enum__emoji_hair_style__bald",
    )
} | {("plurals", "unit__hours__written")})


class PolicyError(Exception):
    pass


def git(repo, *args):
    result = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, check=False
    )
    if result.returncode:
        error = result.stderr.decode(errors="replace").strip()
        raise PolicyError(f"git {' '.join(args[:2])} failed: {error}")
    return result.stdout


def changed_files(repo, base, head):
    entries = git(
        repo, "diff", "--name-status", "--no-renames", "-z", base, head, "--"
    ).split(b"\0")
    if entries[-1] != b"":
        raise PolicyError("Could not read the complete Git file list")
    entries.pop()
    if len(entries) % 2:
        raise PolicyError("Unexpected Git file-list format")
    return [
        (entries[i].decode(), entries[i + 1].decode())
        for i in range(0, len(entries), 2)
    ]


def is_resource_file(path):
    return path == SOURCE or LOCALIZED.fullmatch(path) is not None


def resource_paths(repo, base):
    names = git(
        repo, "ls-tree", "-r", "--name-only", "-z", base, "--", "app/src/main/res"
    ).split(b"\0")
    return [name.decode() for name in names if name and is_resource_file(name.decode())]


def read_xml(repo, commit, path):
    entry = git(repo, "ls-tree", "-z", commit, "--", path)
    if not entry.startswith(b"100644 blob ") or not entry.endswith(
        b"\t" + path.encode() + b"\0"
    ):
        raise PolicyError(f"{path}: expected a regular, unchanged-mode XML file")
    size = int(git(repo, "cat-file", "-s", f"{commit}:{path}"))
    if size > MAX_XML_BYTES:
        raise PolicyError(f"{path}: XML file exceeds the review limit")
    data = git(repo, "show", f"{commit}:{path}")
    try:
        decoded = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise PolicyError(f"{path}: expected UTF-8 XML") from exc
    if "\0" in decoded or "<!DOCTYPE" in decoded or "<!ENTITY" in decoded:
        raise PolicyError(f"{path}: document types and entities are not allowed")
    try:
        document = minidom.parseString(data)
    except Exception as exc:
        raise PolicyError(f"{path}: invalid XML: {exc}") from exc
    if document.documentElement.tagName != "resources":
        raise PolicyError(f"{path}: expected a resources root")
    return document


def identity(element):
    return (element.tagName, element.getAttribute("name"))


def node_shape(node):
    if node.nodeType == Node.ELEMENT_NODE:
        attributes = tuple(
            sorted((attribute.name, attribute.value) for attribute in node.attributes.values())
        )
        children = tuple(node_shape(child) for child in node.childNodes)
        return ("element", node.tagName, attributes, children)
    if node.nodeType == Node.TEXT_NODE:
        return ("text", node.data)
    if node.nodeType == Node.CDATA_SECTION_NODE:
        return ("cdata", node.data)
    if node.nodeType == Node.COMMENT_NODE:
        return ("comment", node.data)
    if node.nodeType == Node.PROCESSING_INSTRUCTION_NODE:
        return ("processing-instruction", node.target, node.data)
    raise PolicyError("Unsupported XML node in strings.xml")


def document_shape(document, *, omit_allowlisted=False):
    removed = []
    document_children = []
    for child in document.childNodes:
        if child.nodeType != Node.ELEMENT_NODE:
            document_children.append(node_shape(child))
            continue
        if child is not document.documentElement:
            raise PolicyError("Multiple XML roots are not allowed")
        attributes = tuple(
            sorted((attribute.name, attribute.value) for attribute in child.attributes.values())
        )
        resources = []
        for resource in child.childNodes:
            if resource.nodeType == Node.TEXT_NODE and not resource.data.strip():
                continue  # Indentation around resource elements has no XML meaning.
            if (
                resource.nodeType == Node.ELEMENT_NODE
                and omit_allowlisted
                and identity(resource) in ALLOWLIST
            ):
                removed.append(identity(resource))
                continue
            resources.append(node_shape(resource))
        document_children.append(("element", "resources", attributes, tuple(resources)))
    return (
        (document.version, document.encoding, document.standalone, tuple(document_children)),
        removed,
    )


def check_file_removal(repo, base, head, path):
    before = read_xml(repo, base, path)
    after = read_xml(repo, head, path)
    expected, removed = document_shape(before, omit_allowlisted=True)
    actual, _ = document_shape(after)
    if not removed:
        raise PolicyError(f"{path}: no allowlisted resource was removed")
    if expected != actual:
        raise PolicyError(
            f"{path}: only allowlisted element deletion is permitted; "
            "surviving values, order, and metadata must stay unchanged"
        )
    return removed


def check_pr(event, base, head, issue_state):
    pr = event.get("pull_request", {})
    if pr.get("base", {}).get("sha") != base or pr.get("head", {}).get("sha") != head:
        raise PolicyError("The checked Git commits do not match the pull request event")
    if (
        event.get("repository", {}).get("full_name") != REPOSITORY
        or pr.get("base", {}).get("repo", {}).get("full_name") != REPOSITORY
        or pr.get("base", {}).get("ref") != "main"
        or (pr.get("head", {}).get("repo") or {}).get("full_name") != REPOSITORY
        or pr.get("user", {}).get("login") != MAINTAINER
        or issue_state != "open"
        or ISSUE_LINK.search(pr.get("body") or "") is None
    ):
        raise PolicyError(
            "Localized deletion requires an in-repository maintainer PR "
            "linked to open issue #131"
        )


def get_issue_state():
    result = subprocess.run(
        ["gh", "api", f"repos/{REPOSITORY}/issues/{ISSUE}", "--jq", ".state"],
        capture_output=True, text=True, check=False, timeout=30,
    )
    if result.returncode:
        raise PolicyError("Could not verify the maintenance issue state")
    return result.stdout.strip()


def validate(repo, base, head, event, issue_state=None):
    if not re.fullmatch(r"[0-9a-f]{40}", base) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise PolicyError("Expected full Git commit IDs")
    changes = changed_files(repo, base, head)
    if not any(LOCALIZED.fullmatch(path) for _, path in changes):
        return "No localized strings.xml files were changed."

    check_pr(event, base, head, issue_state if issue_state is not None else get_issue_state())
    if any(status != "M" or not is_resource_file(path) for status, path in changes):
        raise PolicyError(
            "The issue #131 exception permits only edits to existing strings.xml resource files"
        )
    changed = {path for _, path in changes}
    expected_paths = set()
    present = set()
    for path in resource_paths(repo, base):
        before = read_xml(repo, base, path)
        _, found = document_shape(before, omit_allowlisted=True)
        if found:
            expected_paths.add(path)
            present.update(found)
    if present != ALLOWLIST:
        raise PolicyError(
            "The frozen 63-key inventory no longer matches the base; review the policy first"
        )
    if changed != expected_paths:
        raise PolicyError(
            "The PR must remove all 63 keys from every source and localized file "
            "in one resource-only change"
        )

    removed = []
    for path in sorted(changed):
        removed.extend(check_file_removal(repo, base, head, path))
    if set(removed) != ALLOWLIST:
        raise PolicyError("The PR did not remove the complete allowlist")
    return (
        f"Validated deletion of {len(removed)} declarations for all 63 allowlisted "
        f"keys in {len(changed)} resource files."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--event", type=Path, required=True)
    args = parser.parse_args()
    try:
        event = json.loads(args.event.read_text(encoding="utf-8"))
        print(validate(Path.cwd(), args.base, args.head, event))
    except (OSError, ValueError, PolicyError, subprocess.TimeoutExpired) as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

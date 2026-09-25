#!/usr/bin/env bash
set -euo pipefail

# This file doubles as a fake aapt for the policy's small negative tests.
if [[ "${1:-}" == "dump" ]]; then
  case "${2:-}" in
    permissions)
      echo "package: dev.patrickgold.florisboard"
      if [[ "${TEST_CASE:-}" == "internet" ]]; then
        echo "uses-permission: name='android.permission.INTERNET'"
      fi
      ;;
    xmltree)
      echo "E: manifest (line=2)"
      case "${TEST_CASE:-}" in
        debuggable) echo "A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff" ;;
        debuggable_one) echo "A: android:debuggable(0x0101000f)=(type 0x12)0x1" ;;
        harness_name) echo "E: activity (EditorHarnessActivity)" ;;
        harness_action) echo "A: name=dev.patrickgold.florisboard.test.action.EDITOR_HARNESS" ;;
        aapt_failure) exit 7 ;;
      esac
      ;;
    *) exit 2 ;;
  esac
  exit 0
fi

policy_dir="$(cd "$(dirname "$0")" && pwd)"
readonly policy="$policy_dir/verify-packaged-privacy.sh"
readonly fixture="$0"

TEST_CASE=safe bash "$policy" "$fixture" "$fixture" >/dev/null
for case_name in internet debuggable debuggable_one harness_name harness_action aapt_failure; do
  if TEST_CASE="$case_name" bash "$policy" "$fixture" "$fixture" >/dev/null 2>&1; then
    echo "Privacy policy accepted unsafe fixture: $case_name" >&2
    exit 1
  fi
done

echo "Packaged privacy policy fixtures passed."

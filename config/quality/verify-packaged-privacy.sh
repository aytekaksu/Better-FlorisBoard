#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 APK AAPT" >&2
  exit 2
fi

readonly apk="$1"
readonly aapt="$2"
test -f "$apk"
test -x "$aapt"

permissions="$("$aapt" dump permissions "$apk")"
if grep -Fq "android.permission.INTERNET" <<< "$permissions"; then
  echo "The production-like APK must not request INTERNET permission." >&2
  exit 1
fi

manifest="$("$aapt" dump xmltree "$apk" AndroidManifest.xml)"
if grep -Eq "android:debuggable.*(0xffffffff|0x1)$" <<< "$manifest"; then
  echo "The production-like APK must not be debuggable." >&2
  exit 1
fi
if grep -Fq "EditorHarnessActivity" <<< "$manifest" ||
  grep -Fq "dev.patrickgold.florisboard.test.action.EDITOR_HARNESS" <<< "$manifest"; then
  echo "The production-like APK must not contain the debug-only editor harness." >&2
  exit 1
fi

echo "Beta APK privacy check passed."

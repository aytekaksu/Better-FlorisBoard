#!/usr/bin/env bash

set -euo pipefail

readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly checker="$script_dir/require-maintainer-approval.sh"
readonly maintainer="aytekaksu"
readonly head="current-head"
passed=0

expect_pass() {
  local name="$1"
  local author="$2"
  local reviews="$3"

  if ! printf '%s' "$reviews" |
    MAINTAINER_LOGIN="$maintainer" PR_AUTHOR="$author" PR_HEAD_SHA="$head" bash "$checker"; then
    echo "Expected pass: $name" >&2
    exit 1
  fi
  passed=$((passed + 1))
}

expect_fail() {
  local name="$1"
  local author="$2"
  local reviews="$3"

  if printf '%s' "$reviews" |
    MAINTAINER_LOGIN="$maintainer" PR_AUTHOR="$author" PR_HEAD_SHA="$head" bash "$checker" \
      >/dev/null 2>&1; then
    echo "Expected failure: $name" >&2
    exit 1
  fi
  passed=$((passed + 1))
}

expect_pass "maintainer PR needs no review" "$maintainer" "not JSON"
expect_pass \
  "current maintainer approval" \
  "external-contributor" \
  '[[{"id":1,"user":{"login":"aytekaksu"},"state":"APPROVED","commit_id":"current-head"}]]'
expect_pass \
  "later approval supersedes changes request" \
  "external-contributor" \
  '[[
    {"id":1,"user":{"login":"aytekaksu"},"state":"CHANGES_REQUESTED","commit_id":"current-head"},
    {"id":2,"user":{"login":"aytekaksu"},"state":"APPROVED","commit_id":"current-head"}
  ]]'

expect_fail "missing approval" "external-contributor" '[[]]'
expect_fail \
  "another reviewer cannot approve" \
  "external-contributor" \
  '[[{"id":1,"user":{"login":"someone-else"},"state":"APPROVED","commit_id":"current-head"}]]'
expect_fail \
  "stale approval" \
  "external-contributor" \
  '[[{"id":1,"user":{"login":"aytekaksu"},"state":"APPROVED","commit_id":"old-head"}]]'
expect_fail \
  "later changes request supersedes approval" \
  "external-contributor" \
  '[[
    {"id":1,"user":{"login":"aytekaksu"},"state":"APPROVED","commit_id":"current-head"},
    {"id":2,"user":{"login":"aytekaksu"},"state":"CHANGES_REQUESTED","commit_id":"current-head"}
  ]]'
expect_fail \
  "dismissed approval" \
  "external-contributor" \
  '[[{"id":1,"user":{"login":"aytekaksu"},"state":"DISMISSED","commit_id":"current-head"}]]'

echo "Governance policy tests passed: $passed"

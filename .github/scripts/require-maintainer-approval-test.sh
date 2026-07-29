#!/usr/bin/env bash

set -euo pipefail

readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly approval_checker="$script_dir/require-maintainer-approval.sh"
readonly ci_checker="$script_dir/require-external-ci.sh"
readonly maintainer="aytekaksu"
readonly head="current-head"
passed=0

expect_pass() {
  local name="$1"
  local author="$2"
  local reviews="$3"

  if ! printf '%s' "$reviews" |
    MAINTAINER_LOGIN="$maintainer" PR_AUTHOR="$author" PR_HEAD_SHA="$head" \
      bash "$approval_checker"; then
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
    MAINTAINER_LOGIN="$maintainer" PR_AUTHOR="$author" PR_HEAD_SHA="$head" \
      bash "$approval_checker" \
      >/dev/null 2>&1; then
    echo "Expected failure: $name" >&2
    exit 1
  fi
  passed=$((passed + 1))
}

expect_ci_result() {
  local name="$1"
  local expected="$2"
  local checks="$3"
  local actual=0

  set +e
  printf '%s' "$checks" | bash "$ci_checker" >/dev/null 2>&1
  actual=$?
  set -e

  if [[ "$actual" -ne "$expected" ]]; then
    echo "Expected CI result $expected, got $actual: $name" >&2
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

expect_ci_result "checks not started" 2 '[{"check_runs":[]}]'
expect_ci_result \
  "checks still running" \
  2 \
  '[{"check_runs":[
    {"id":1,"name":"validate","status":"completed","conclusion":"success","app":{"id":15368}},
    {"id":2,"name":"build","status":"in_progress","conclusion":null,"app":{"id":15368}}
  ]}]'
expect_ci_result \
  "required check failed" \
  1 \
  '[{"check_runs":[
    {"id":1,"name":"validate","status":"completed","conclusion":"success","app":{"id":15368}},
    {"id":2,"name":"build","status":"completed","conclusion":"failure","app":{"id":15368}}
  ]}]'
expect_ci_result \
  "both required checks passed across pages" \
  0 \
  '[
    {"check_runs":[
      {"id":1,"name":"validate","status":"completed","conclusion":"success","app":{"id":15368}}
    ]},
    {"check_runs":[
      {"id":2,"name":"build","status":"completed","conclusion":"success","app":{"id":15368}}
    ]}
  ]'
expect_ci_result \
  "latest successful rerun wins" \
  0 \
  '[{"check_runs":[
    {"id":1,"name":"validate","status":"completed","conclusion":"success","app":{"id":15368}},
    {"id":2,"name":"build","status":"completed","conclusion":"failure","app":{"id":15368}},
    {"id":3,"name":"build","status":"completed","conclusion":"success","app":{"id":15368}}
  ]}]'
expect_ci_result \
  "unexpected app is ignored" \
  2 \
  '[{"check_runs":[
    {"id":1,"name":"validate","status":"completed","conclusion":"success","app":{"id":999}},
    {"id":2,"name":"build","status":"completed","conclusion":"success","app":{"id":999}}
  ]}]'

echo "Governance policy tests passed: $passed"

#!/usr/bin/env bash

set -euo pipefail

readonly github_actions_app_id="${GITHUB_ACTIONS_APP_ID:-15368}"
readonly result="$(
  jq -r \
    --argjson app_id "$github_actions_app_id" \
    '
      def runs:
        [
          .[]
          | .check_runs[]
          | select(.app.id == $app_id)
        ];
      def latest($name):
        [runs[] | select(.name == $name)]
        | sort_by(.id)
        | last;

      [latest("build"), latest("validate")] as $required
      | if any($required[]; . == null or .status != "completed") then
          "pending"
        elif all(
          $required[];
          .conclusion == "success"
          or .conclusion == "neutral"
          or .conclusion == "skipped"
        ) then
          "success"
        else
          "failure"
        end
    '
)"

case "$result" in
  success)
    echo "Required outside-contribution CI passed."
    exit 0
    ;;
  pending)
    echo "Required outside-contribution CI is still pending."
    exit 2
    ;;
  failure)
    echo "::error::Outside-contribution CI failed."
    exit 1
    ;;
  *)
    echo "::error::Unknown outside-contribution CI state: $result"
    exit 1
    ;;
esac

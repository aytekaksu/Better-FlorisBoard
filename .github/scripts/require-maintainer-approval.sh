#!/usr/bin/env bash

set -euo pipefail

readonly maintainer_login="${MAINTAINER_LOGIN:?MAINTAINER_LOGIN is required}"
readonly pr_head_sha="${PR_HEAD_SHA:?PR_HEAD_SHA is required}"

if jq -e \
  --arg maintainer "$maintainer_login" \
  --arg head "$pr_head_sha" \
  '
    flatten
    | [
        .[]
        | select(.user.login == $maintainer)
        | select(
            .state == "APPROVED"
            or .state == "CHANGES_REQUESTED"
            or .state == "DISMISSED"
          )
      ]
    | sort_by(.id)
    | last
    | .state == "APPROVED" and .commit_id == $head
  ' >/dev/null; then
  echo "Outside contribution approved by @$maintainer_login at $pr_head_sha."
  exit 0
fi

echo "::error::Outside contributions require @$maintainer_login to approve the current head commit."
exit 1

#!/usr/bin/env bash

set -euo pipefail

readonly maintainer_login="${MAINTAINER_LOGIN:?MAINTAINER_LOGIN is required}"
readonly pr_author="${PR_AUTHOR:?PR_AUTHOR is required}"
readonly pr_head_sha="${PR_HEAD_SHA:?PR_HEAD_SHA is required}"

if [[ "$pr_author" == "$maintainer_login" ]]; then
  # Drain piped API output so callers using `set -o pipefail` do not observe
  # an upstream SIGPIPE when the owner shortcut returns immediately.
  cat >/dev/null
  echo "Maintainer-authored pull request: no self-approval required."
  exit 0
fi

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

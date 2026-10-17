#!/usr/bin/env bash
# Qualification must read the committed dependency trust root, never a capture's rewrites.
#
# A capture run (`--write-locks --write-verification-metadata`) rewrites
# gradle/verification-metadata.xml and every *gradle.lockfile in the checkout, and can create new
# lockfiles. With RESTORE_CAPTURE=true this restores the tracked files from HEAD and deletes
# untracked lockfiles. In every mode it then fails unless those bytes equal HEAD.
set -euo pipefail

trust_root=(gradle/verification-metadata.xml ':(glob)**/*gradle.lockfile')

if [[ "${RESTORE_CAPTURE:-false}" == "true" ]]; then
  git restore --source=HEAD --staged --worktree -- "${trust_root[@]}"
  while IFS= read -r -d '' created; do
    rm -f -- "$created"
  done < <(git ls-files --others -z -- ':(glob)**/*gradle.lockfile')
fi

if ! git diff --quiet HEAD -- "${trust_root[@]}"; then
  echo "::error::Dependency qualification would read a changed trust root:" >&2
  git diff --stat HEAD -- "${trust_root[@]}" >&2
  exit 1
fi
untracked=$(git ls-files --others -- ':(glob)**/*gradle.lockfile')
if [[ -n "$untracked" ]]; then
  echo "::error::Dependency qualification would read uncommitted lockfiles:" >&2
  echo "$untracked" >&2
  exit 1
fi
echo "Dependency qualification reads the committed trust root"

#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"
release_tag=${PR_PREVIEW_RELEASE_TAG:-android-pr-previews}
repository="repos/$GITHUB_REPOSITORY"

[[ "$GITHUB_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]
[[ "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]]
[[ "$release_tag" =~ ^[A-Za-z0-9._-]+$ ]]

operation=${1:?Expected check-capacity or prune}
shift
case "$operation" in
  check-capacity)
    [[ $# -ge 1 ]]
    names=()
    for channel in "$@"; do
      [[ "$channel" == stable || "$channel" == isolated ]]
      names+=("whitenoise-pr-${PR_NUMBER}-${channel}.apk")
    done
    ;;
  prune)
    [[ $# -eq 0 ]]
    ;;
  *)
    printf 'Unknown preview asset operation: %s\n' "$operation" >&2
    exit 2
    ;;
esac

# Read all assets in one release response. A deletion by another PR can
# shift assets between numbered pages while a paginated list is being read.
if ! assets=$(gh api "$repository/releases/tags/$release_tag" --jq .assets 2>&1); then
  if [[ "$assets" == *' (HTTP 404)' ]]; then
    exit 0 # No release exists before the first preview.
  fi
  printf '%s\n' "$assets" >&2
  exit 1
fi
jq -e 'type == "array"' <<< "$assets" >/dev/null

if [[ "$operation" == check-capacity ]]; then
  # Leave room for independent PRs that may be uploading concurrently.
  # Replacing existing names does not consume slots.
  capacity=950
  counts=$(jq -r --args '
    [length, (($ARGS.positional | unique) - map(.name) | length)] | @tsv
  ' "${names[@]}" <<< "$assets")
  read -r count new_count <<< "$counts"
  if (( new_count > 0 && count + new_count > capacity )); then
    printf 'Preview release %s has %d assets and needs %d new slots (publishing ceiling %d; GitHub limit 1000). Remove closed-PR previews and rerun.\n' \
      "$release_tag" "$count" "$new_count" "$capacity" >&2
    exit 1
  fi
  exit 0
fi

asset_ids=$(jq -r --arg name "whitenoise-pr-${PR_NUMBER}" '
  .[] | select(.name == ($name + "-stable.apk") or .name == ($name + "-isolated.apk")) | .id
' <<< "$assets")
[[ -n "$asset_ids" ]] || exit 0

# A close event may have been queued before the PR was reopened. Recheck
# live state while holding the same per-PR concurrency group as the publisher.
state=$(gh api "$repository/pulls/$PR_NUMBER" --jq .state)
if [[ "$state" == open ]]; then
  exit 0
fi
[[ "$state" == closed ]]

while IFS= read -r asset_id; do
  [[ "$asset_id" =~ ^[1-9][0-9]*$ ]]
  gh api --method DELETE "$repository/releases/assets/$asset_id"
  printf 'Removed preview asset %s for PR #%s.\n' "$asset_id" "$PR_NUMBER"
done <<< "$asset_ids"

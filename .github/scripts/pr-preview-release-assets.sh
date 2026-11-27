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

# A missing release is expected before the first preview. Other errors must
# stop the job, especially a rate limit or an incomplete asset inventory.
if ! release_id=$(gh api "$repository/releases/tags/$release_tag" --jq .id 2>&1); then
  if [[ "$release_id" == *' (HTTP 404)' ]]; then
    exit 0
  fi
  printf '%s\n' "$release_id" >&2
  exit 1
fi
[[ "$release_id" =~ ^[1-9][0-9]*$ ]]
assets=$(gh api "$repository/releases/$release_id/assets?per_page=100" --paginate --slurp | jq -c 'add // []')

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

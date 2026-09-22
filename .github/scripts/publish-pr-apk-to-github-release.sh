#!/usr/bin/env bash
set -euo pipefail

: "${APK_PATH:?APK_PATH is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${PREVIEW_CHANNEL:?PREVIEW_CHANNEL is required}"

release_tag=${PR_PREVIEW_RELEASE_TAG:-android-pr-previews}
max_attempts=${PR_PREVIEW_SERVE_MAX_ATTEMPTS:-3}
backoff_seconds=${PR_PREVIEW_SERVE_BACKOFF_SECONDS:-5}
expected_mime=application/vnd.android.package-archive

[[ -f "$APK_PATH" ]] || {
  printf 'APK_PATH does not name a file: %s\n' "$APK_PATH" >&2
  exit 2
}
[[ "$GITHUB_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
  printf 'GITHUB_REPOSITORY must be an owner/repository pair\n' >&2
  exit 2
}
[[ "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]] || {
  printf 'PR_NUMBER must be a positive integer\n' >&2
  exit 2
}
[[ "$PREVIEW_CHANNEL" == stable || "$PREVIEW_CHANNEL" == isolated ]] || {
  printf 'PREVIEW_CHANNEL must be stable or isolated\n' >&2
  exit 2
}
[[ "$release_tag" =~ ^[A-Za-z0-9._-]+$ ]] || {
  printf 'PR_PREVIEW_RELEASE_TAG contains unsupported characters\n' >&2
  exit 2
}
[[ "$max_attempts" =~ ^[1-5]$ ]] || {
  printf 'PR_PREVIEW_SERVE_MAX_ATTEMPTS must be between 1 and 5\n' >&2
  exit 2
}
if ! [[ "$backoff_seconds" =~ ^[0-9]+$ ]] || (( backoff_seconds > 60 )); then
  printf 'PR_PREVIEW_SERVE_BACKOFF_SECONDS must be between 0 and 60\n' >&2
  exit 2
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
asset_name="whitenoise-pr-${PR_NUMBER}-${PREVIEW_CHANNEL}.apk"
staged_apk="$tmp/$asset_name"
cp "$APK_PATH" "$staged_apk"
url="https://github.com/${GITHUB_REPOSITORY}/releases/download/${release_tag}/${asset_name}"

ensure_release() {
  if gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
    return 0
  fi

  # Different PRs publish concurrently. If another run wins the one-time
  # release creation race, accept its release only after resolving it again.
  if ! gh release create "$release_tag" \
    --repo "$GITHUB_REPOSITORY" \
    --title 'Android PR Previews' \
    --notes 'Signed Android PR preview assets. Each PR keeps stable and isolated update-in-place APK links.' \
    --prerelease; then
    gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" >/dev/null
  fi
}

normalize_mime() {
  local value=${1%%;*}
  value=${value,,}
  value=${value#"${value%%[![:space:]]*}"}
  value=${value%"${value##*[![:space:]]}"}
  printf '%s' "$value"
}

verify_browser_install_contract() {
  local attempt headers curl_status served_type disposition delay

  for (( attempt = 1; attempt <= max_attempts; attempt++ )); do
    set +e
    headers=$(curl --fail --silent --show-error --location --head --max-time 30 "$url")
    curl_status=$?
    set -e
    served_type=$(
      printf '%s' "$headers" \
        | awk -F': ' 'tolower($1) == "content-type" { sub(/\r$/, "", $2); value=$2 } END { print value }'
    )
    disposition=$(
      printf '%s' "$headers" \
        | awk -F': ' 'tolower($1) == "content-disposition" { sub(/\r$/, "", $2); value=$2 } END { print value }'
    )

    if (( curl_status == 0 )) && \
      [[ "$(normalize_mime "$served_type")" == "$expected_mime" ]] && \
      [[ "${disposition,,}" == attachment* ]] && \
      [[ "${disposition,,}" == *"filename=${asset_name,,}"* ]]; then
      return 0
    fi

    if (( attempt == max_attempts )); then
      printf 'Published APK is not browser-installable: status=%d type=%s disposition=%s\n' \
        "$curl_status" "$served_type" "$disposition" >&2
      return 1
    fi
    delay=$((backoff_seconds * (1 << (attempt - 1))))
    printf 'Published APK headers are not ready (attempt %d/%d); retrying in %ds.\n' \
      "$attempt" "$max_attempts" "$delay" >&2
    sleep "$delay"
  done
}

ensure_release
gh release upload "$release_tag" "$staged_apk" --repo "$GITHUB_REPOSITORY" --clobber
verify_browser_install_contract
printf '%s\n' "$url"

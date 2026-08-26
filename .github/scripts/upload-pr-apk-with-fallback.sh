#!/usr/bin/env bash
set -euo pipefail

: "${APK_PATH:?APK_PATH is required}"
: "${BLOSSOM_SERVERS:?BLOSSOM_SERVERS is required}"
: "${BLOSSOM_UPLOAD_NSEC:?BLOSSOM_UPLOAD_NSEC is required}"

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
uploader=${BLOSSOM_UPLOADER:-"$script_dir/upload-pr-apk-to-blossom.sh"}
read -r -a servers <<< "$BLOSSOM_SERVERS"

if (( ${#servers[@]} < 1 || ${#servers[@]} > 5 )); then
  printf 'BLOSSOM_SERVERS must contain between 1 and 5 origins\n' >&2
  exit 2
fi
if [[ ! -x "$uploader" ]]; then
  printf 'Blossom uploader is not executable: %s\n' "$uploader" >&2
  exit 2
fi

last_status=1
for server in "${servers[@]}"; do
  set +e
  url=$(BLOSSOM_SERVER="$server" "$uploader")
  status=$?
  set -e
  if (( status == 0 )); then
    printf '%s\n' "$url"
    exit 0
  fi
  last_status=$status
  printf 'Blossom upload failed via %s (status %d); trying the next origin.\n' \
    "$server" "$status" >&2
done

printf 'Blossom upload failed via every configured origin.\n' >&2
exit "$last_status"

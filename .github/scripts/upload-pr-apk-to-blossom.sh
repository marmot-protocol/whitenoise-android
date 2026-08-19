#!/usr/bin/env bash
set -euo pipefail

: "${APK_PATH:?APK_PATH is required}"
: "${BLOSSOM_SERVER:?BLOSSOM_SERVER is required}"
: "${BLOSSOM_UPLOAD_NSEC:?BLOSSOM_UPLOAD_NSEC is required}"

nak_bin=${NAK_BIN:-"$(go env GOPATH)/bin/nak"}
max_attempts=${BLOSSOM_UPLOAD_MAX_ATTEMPTS:-3}
attempt_timeout_seconds=${BLOSSOM_UPLOAD_TIMEOUT_SECONDS:-240}
backoff_seconds=${BLOSSOM_UPLOAD_BACKOFF_SECONDS:-10}

if ! [[ "$max_attempts" =~ ^[1-5]$ ]]; then
  printf 'BLOSSOM_UPLOAD_MAX_ATTEMPTS must be between 1 and 5\n' >&2
  exit 2
fi
if ! [[ "$attempt_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || (( attempt_timeout_seconds > 600 )); then
  printf 'BLOSSOM_UPLOAD_TIMEOUT_SECONDS must be between 1 and 600\n' >&2
  exit 2
fi
if ! [[ "$backoff_seconds" =~ ^[0-9]+$ ]] || (( backoff_seconds > 60 )); then
  printf 'BLOSSOM_UPLOAD_BACKOFF_SECONDS must be between 0 and 60\n' >&2
  exit 2
fi

apk_sha256=$(sha256sum "$APK_PATH" | awk '{print $1}')
expected_mime=${BLOSSOM_APK_MIME:-application/vnd.android.package-archive}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# Upload by file path (not stdin) so nak sends the APK MIME type. Stdin uploads
# store blobs as application/zip on nostr.download, which makes browsers save
# hash.apk.zip instead of a plain .apk.
staging_apk="$tmp/preview-upload.apk"
cp -- "$APK_PATH" "$staging_apk"

for (( attempt = 1; attempt <= max_attempts; attempt++ )); do
  stdout="$tmp/stdout"
  stderr="$tmp/stderr"
  timeout_stderr="$tmp/timeout-stderr"

  set +e
  # The single-quoted command expands only inside the isolated child shell.
  # shellcheck disable=SC2016
  NAK_BIN_VALUE="$nak_bin" APK_STAGING="$staging_apk" LC_ALL=C \
    timeout --verbose --signal=TERM --kill-after=10s "${attempt_timeout_seconds}s" \
      bash -c 'exec "$NAK_BIN_VALUE" blossom upload --server "$BLOSSOM_SERVER" --sec "$BLOSSOM_UPLOAD_NSEC" "$APK_STAGING" 2>&3' \
      > "$stdout" 2> "$timeout_stderr" 3> "$stderr"
  status=$?
  set -e

  if (( status == 0 )); then
    if ! returned=$(jq -er '.sha256 | select(type == "string")' < "$stdout" 2>/dev/null) || \
      [[ -z "$returned" ]]; then
      printf 'Blossom upload returned an invalid response\n' >&2
      exit 1
    fi
    if [[ "$returned" != "$apk_sha256" ]]; then
      printf 'Blossom upload sha mismatch: local=%s returned=%s\n' \
        "$apk_sha256" "$returned" >&2
      exit 1
    fi
    if ! returned_type=$(jq -er '.type | select(type == "string")' < "$stdout" 2>/dev/null) || \
      [[ -z "$returned_type" ]]; then
      printf 'Blossom upload returned an invalid MIME type\n' >&2
      exit 1
    fi
    if [[ "$returned_type" != "$expected_mime" ]]; then
      printf 'Blossom stored APK as %s instead of %s\n' "$returned_type" "$expected_mime" >&2
      exit 1
    fi
    url=$(printf '%s/%s.apk' "${BLOSSOM_SERVER%/}" "$apk_sha256")
    if [[ "${BLOSSOM_SKIP_SERVE_CHECK:-}" != 1 ]]; then
      served_type=$(
        curl --fail --silent --show-error --head --max-time 30 "$url" \
          | awk -F': ' 'tolower($1) == "content-type" { sub(/\r$/, "", $2); print $2; exit }'
      )
      if [[ "$served_type" != "$expected_mime" ]]; then
        printf 'Blossom serves %s as %s instead of %s\n' "$url" "$served_type" "$expected_mime" >&2
        exit 1
      fi
    fi
    printf '%s\n' "$url"
    exit 0
  fi

  error=$(<"$stderr")
  timeout_error=$(<"$timeout_stderr")
  if [[ -n "$error" ]]; then
    printf '%s\n' "$error" >&2
  fi
  if [[ -n "$timeout_error" ]]; then
    printf '%s\n' "$timeout_error" >&2
  fi

  transient=false
  if (( status == 124 )); then
    transient=true
  elif (( status == 137 )) && \
    [[ "$timeout_error" == *'timeout: sending signal KILL to command '* ]]; then
    transient=true
  elif [[ "$error" =~ upload\ returned\ an\ error\ \(([0-9]{3})\) ]]; then
    if [[ "${BASH_REMATCH[1]}" =~ ^5 ]]; then
      transient=true
    fi
  else
    error_lower=${error,,}
    case "$error_lower" in
      *'connection reset by peer'* | *'connection refused'* | \
      *'connection timed out'* | *'network is unreachable'* | \
      *'dialing to the given tcp address timed out'* | \
      *'no route to host'* | *'temporary failure in name resolution'* | \
      *'server misbehaving'* | *'tls handshake timeout'* | \
      *'i/o timeout'* | *'context deadline exceeded'* | \
      *'unexpected eof'* | eof | *': eof'* | *'broken pipe'* | \
      *'use of closed network connection'*)
        transient=true
        ;;
    esac
  fi

  if (( attempt == max_attempts )) || [[ "$transient" != true ]]; then
    exit "$status"
  fi

  delay=$((backoff_seconds * (1 << (attempt - 1))))
  printf 'Transient Blossom upload failure (attempt %d/%d); retrying in %ds.\n' \
    "$attempt" "$max_attempts" "$delay" >&2
  sleep "$delay"
done

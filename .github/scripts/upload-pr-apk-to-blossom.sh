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
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

for (( attempt = 1; attempt <= max_attempts; attempt++ )); do
  stdout="$tmp/stdout"
  stderr="$tmp/stderr"
  timeout_stderr="$tmp/timeout-stderr"

  set +e
  # The single-quoted command expands only inside the isolated child shell.
  # shellcheck disable=SC2016
  NAK_BIN_VALUE="$nak_bin" LC_ALL=C \
    timeout --verbose --signal=TERM --kill-after=10s "${attempt_timeout_seconds}s" \
      bash -c 'exec "$NAK_BIN_VALUE" blossom upload --server "$BLOSSOM_SERVER" --sec "$BLOSSOM_UPLOAD_NSEC" 2>&3' \
      < "$APK_PATH" > "$stdout" 2> "$timeout_stderr" 3> "$stderr"
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
    printf '%s/%s.apk\n' "${BLOSSOM_SERVER%/}" "$apk_sha256"
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

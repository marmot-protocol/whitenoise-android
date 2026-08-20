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
apk_size=$(wc -c < "$APK_PATH")
apk_size=${apk_size//[[:space:]]/}
expected_mime=${BLOSSOM_APK_MIME:-application/vnd.android.package-archive}
apk_container_mime=application/zip
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [[ "$BLOSSOM_SERVER" =~ ^https://([A-Za-z0-9.-]+)(:[0-9]+)?/?$ ]]; then
  server_host=${BASH_REMATCH[1],,}
else
  printf 'BLOSSOM_SERVER must be an HTTPS origin without a path\n' >&2
  exit 2
fi

normalize_mime() {
  local value=${1%%;*}
  value=${value,,}
  # HTTP permits optional whitespace around a media type and its parameters.
  value=${value#"${value%%[![:space:]]*}"}
  value=${value%"${value##*[![:space:]]}"}
  printf '%s' "$value"
}

is_accepted_apk_mime() {
  local normalized
  normalized=$(normalize_mime "$1")
  # APKs are ZIP containers. nostr.download content-sniffs the signed bytes
  # and reports application/zip even when BUD-02 receives the explicit Android
  # package type. Both values describe the same digest-verified APK bytes.
  [[ "$normalized" == "$expected_base_mime" || "$normalized" == "$apk_container_mime" ]]
}

verify_served_apk() {
  local url=$1
  local serve_attempt headers curl_status served_type delay

  for (( serve_attempt = 1; serve_attempt <= max_attempts; serve_attempt++ )); do
    set +e
    headers=$(curl --fail --silent --show-error --head --max-time 30 "$url")
    curl_status=$?
    set -e
    served_type=$(
      printf '%s' "$headers" \
        | awk -F': ' 'tolower($1) == "content-type" { sub(/\r$/, "", $2); print $2; exit }'
    )
    if (( curl_status == 0 )) && is_accepted_apk_mime "$served_type"; then
      return 0
    fi

    if (( curl_status != 0 )); then
      printf 'Blossom serve verification failed for %s (curl status %d)\n' \
        "$url" "$curl_status" >&2
    else
      printf 'Blossom serves %s as unsupported APK MIME %s\n' "$url" "$served_type" >&2
    fi
    if (( serve_attempt == max_attempts )); then
      return 1
    fi
    delay=$((backoff_seconds * (1 << (serve_attempt - 1))))
    printf 'Transient Blossom serve verification failure (attempt %d/%d); retrying in %ds.\n' \
      "$serve_attempt" "$max_attempts" "$delay" >&2
    sleep "$delay"
  done
}

expected_base_mime=$(normalize_mime "$expected_mime")

for (( attempt = 1; attempt <= max_attempts; attempt++ )); do
  stdout="$tmp/stdout"
  stderr="$tmp/stderr"
  http_status_file="$tmp/http-status"

  # nak's blossom uploader detects an APK as application/zip from its ZIP
  # signature and offers no MIME override. Generate only the short-lived,
  # hash- and server-scoped BUD-11 authorization with nak, then perform the
  # BUD-02 PUT ourselves so the APK media type is explicit on the wire.
  expiration=$(( $(date +%s) + attempt_timeout_seconds + 60 ))
  if ! authorization_event=$(
    NOSTR_SECRET_KEY="$BLOSSOM_UPLOAD_NSEC" "$nak_bin" event \
      --kind 24242 \
      --content 'Upload White Noise PR preview APK' \
      --tag t=upload \
      --tag "x=$apk_sha256" \
      --tag "server=$server_host" \
      --tag "expiration=$expiration" \
      </dev/null
  ); then
    printf 'Failed to create Blossom upload authorization\n' >&2
    exit 1
  fi
  if ! jq -e \
    --arg hash "$apk_sha256" \
    --arg host "$server_host" \
    '.kind == 24242 and (.id | type == "string") and (.sig | type == "string") and
      any(.tags[]; . == ["t", "upload"]) and
      any(.tags[]; . == ["x", $hash]) and
      any(.tags[]; . == ["server", $host])' \
    <<< "$authorization_event" >/dev/null; then
    printf 'nak returned an invalid Blossom upload authorization\n' >&2
    exit 1
  fi
  authorization=$(
    printf '%s' "$authorization_event" \
      | base64 \
      | tr -d '\n' \
      | tr '+/' '-_' \
      | tr -d '='
  )

  set +e
  LC_ALL=C timeout --verbose --signal=TERM --kill-after=10s "${attempt_timeout_seconds}s" \
    curl --silent --show-error \
      --request PUT \
      --output "$stdout" \
      --write-out '%{http_code}' \
      --header "Authorization: Nostr $authorization" \
      --header "Content-Type: $expected_mime" \
      --header "Content-Length: $apk_size" \
      --header "X-SHA-256: $apk_sha256" \
      --data-binary "@$APK_PATH" \
      "${BLOSSOM_SERVER%/}/upload" \
      > "$http_status_file" 2> "$stderr"
  status=$?
  set -e
  http_status=$(<"$http_status_file")

  if (( status == 0 )) && [[ "$http_status" =~ ^2[0-9][0-9]$ ]]; then
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
    if ! is_accepted_apk_mime "$returned_type"; then
      printf 'Blossom stored APK as unsupported MIME %s\n' "$returned_type" >&2
      exit 1
    fi
    url=$(printf '%s/%s.apk' "${BLOSSOM_SERVER%/}" "$apk_sha256")
    if [[ "${BLOSSOM_SKIP_SERVE_CHECK:-}" != 1 ]]; then
      verify_served_apk "$url" || exit 1
    fi
    printf '%s\n' "$url"
    exit 0
  fi

  error=$(<"$stderr")
  if [[ -n "$error" ]]; then
    printf '%s\n' "$error" >&2
  fi
  if (( status == 0 )); then
    printf 'Blossom upload returned HTTP %s\n' "$http_status" >&2
    status=22
  fi

  transient=false
  if (( status == 124 )); then
    transient=true
  elif (( status == 137 )) && \
    [[ "$error" == *'timeout: sending signal KILL to command '* ]]; then
    transient=true
  elif [[ "$http_status" =~ ^5[0-9][0-9]$ ]] || \
    [[ "$http_status" == 408 || "$http_status" == 425 || "$http_status" == 429 ]]; then
    transient=true
  else
    case "$status" in
      7 | 18 | 28 | 35 | 52 | 55 | 56 | 92)
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

#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
uploader="$script_dir/upload-pr-apk-to-blossom.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

apk="$tmp/preview.apk"
printf 'test apk bytes' > "$apk"
expected_sha=$(sha256sum "$apk" | awk '{print $1}')
state="$tmp/attempts"
curl_state="$tmp/curl-attempts"
fake_nak="$tmp/nak"
fake_curl="$tmp/curl"
runner_stdin="$tmp/runner-stdin"
printf 'non-interactive runner stdin\n' > "$runner_stdin"

cat > "$fake_nak" <<'FAKE_NAK'
#!/usr/bin/env bash
set -euo pipefail
[[ -c /dev/stdin ]] || {
  printf 'nak authorization stdin was not detached\n' >&2
  exit 125
}
[[ "$NOSTR_SECRET_KEY" == 'test-secret-must-not-leak' ]]
[[ "$*" == *'--kind 24242'* ]]
[[ "$*" == *'--tag t=upload'* ]]
[[ "$*" == *"--tag x=$FAKE_APK_SHA256"* ]]
[[ "$*" == *'--tag server=example.test'* ]]
if [[ "$FAKE_SCENARIO" == auth-failure ]]; then
  printf 'signer unavailable\n' >&2
  exit 1
fi
printf '{"kind":24242,"id":"test-id","sig":"test-sig","tags":[["t","upload"],["x","%s"],["server","example.test"]]}\n' \
  "$FAKE_APK_SHA256"
FAKE_NAK
chmod +x "$fake_nak"

cat > "$fake_curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
original_args=$*
operation=upload
output=''
data=''
url=''
headers=()
while (( $# > 0 )); do
  case "$1" in
    --head)
      operation=head
      shift
      ;;
    --request | --output | --write-out | --header | --data-binary | --max-time)
      option=$1
      value=$2
      if [[ "$option" == --output ]]; then
        output=$value
      elif [[ "$option" == --header ]]; then
        headers+=("$value")
      elif [[ "$option" == --data-binary ]]; then
        data=$value
      fi
      shift 2
      ;;
    --silent | --show-error | --fail)
      shift
      ;;
    *)
      url=$1
      shift
      ;;
  esac
done

if [[ "$operation" == head ]]; then
  attempt=$(<"$FAKE_CURL_STATE")
  attempt=$((attempt + 1))
  printf '%s\n' "$attempt" > "$FAKE_CURL_STATE"
  case "$FAKE_SERVE_SCENARIO:$attempt" in
    transport:1)
      exit 7
      ;;
    mime-delay:1)
      served_type='application/octet-stream'
      ;;
    *)
      served_type=$FAKE_SERVE_TYPE
      ;;
  esac
  printf 'HTTP/1.1 200 OK\r\nContent-Type: %s\r\nContent-Length: 14\r\n\r\n' "$served_type"
  exit 0
fi

attempt=$(<"$FAKE_NAK_STATE")
attempt=$((attempt + 1))
printf '%s\n' "$attempt" > "$FAKE_NAK_STATE"
[[ "$url" == 'https://example.test/upload' ]]
[[ "$data" == @* && -f "${data#@}" ]]
[[ "$original_args" != *'test-secret-must-not-leak'* ]]
printf '%s\n' "${headers[@]}" | grep -Fxq 'Content-Type: application/vnd.android.package-archive'
printf '%s\n' "${headers[@]}" | grep -Fxq "Content-Length: $FAKE_APK_SIZE"
printf '%s\n' "${headers[@]}" | grep -Fxq "X-SHA-256: $FAKE_APK_SHA256"
authorization=$(printf '%s\n' "${headers[@]}" | sed -n 's/^Authorization: Nostr //p')
[[ "$authorization" =~ ^[A-Za-z0-9_-]+$ && "$authorization" != *'='* ]]

respond() {
  local code=$1 body=$2
  printf '%s\n' "$body" > "$output"
  printf '%s' "$code"
  exit 0
}

case "$FAKE_SCENARIO:$attempt" in
  http-502:1)
    respond 502 '{"error":"bad gateway"}'
    ;;
  transport:1)
    printf '000'
    printf 'curl: connection reset by peer\n' >&2
    exit 56
    ;;
  dialing-timeout:1)
    printf '000'
    printf 'curl: connection timed out\n' >&2
    exit 28
    ;;
  eof:1)
    printf '000'
    printf 'curl: empty reply from server\n' >&2
    exit 52
    ;;
  broken-pipe:1)
    printf '000'
    printf 'curl: failed sending data\n' >&2
    exit 55
    ;;
  timeout:1)
    sleep 2
    ;;
  forced-timeout:1)
    trap '' TERM
    sleep 12
    ;;
  self-sigkill:*)
    kill -KILL "$$"
    ;;
  http-401:*)
    respond 401 '{"error":"unauthorized"}'
    ;;
  always-503:*)
    respond 503 '{"error":"unavailable"}'
    ;;
  hash-mismatch:*)
    respond 201 "{\"sha256\":\"$(printf '%064d' 0)\",\"type\":\"application/vnd.android.package-archive\"}"
    ;;
  empty-output:*)
    respond 201 ''
    ;;
  malformed-output:*)
    respond 201 'not-json'
    ;;
  always-mime-mismatch:*)
    respond 200 "{\"sha256\":\"$FAKE_APK_SHA256\",\"type\":\"text/plain\"}"
    ;;
  zip-upload-mime:*)
    respond 200 "{\"sha256\":\"$FAKE_APK_SHA256\",\"type\":\"application/zip\"}"
    ;;
  parameterized-upload-mime:*)
    respond 201 "{\"sha256\":\"$FAKE_APK_SHA256\",\"type\":\" Application/Vnd.Android.Package-Archive ; charset=binary\"}"
    ;;
  nxdomain:*)
    printf '000'
    printf 'curl: Could not resolve host: example.test\n' >&2
    exit 6
    ;;
esac
respond 201 "{\"sha256\":\"$FAKE_APK_SHA256\",\"type\":\"application/vnd.android.package-archive\"}"
FAKE_CURL
chmod +x "$fake_curl"

run_uploader() {
  local scenario=$1
  local serve_type=${2:-}
  local serve_scenario=${3:-success}
  local skip_serve_check=1
  if [[ -n "$serve_type" ]]; then
    skip_serve_check=''
  fi
  printf '0\n' > "$state"
  printf '0\n' > "$curl_state"
  stdout="$tmp/$scenario.stdout"
  stderr="$tmp/$scenario.stderr"
  set +e
  APK_PATH="$apk" \
    BLOSSOM_SERVER='https://example.test' \
    BLOSSOM_UPLOAD_NSEC='test-secret-must-not-leak' \
    BLOSSOM_SKIP_SERVE_CHECK="$skip_serve_check" \
    NAK_BIN="$fake_nak" \
    PATH="$tmp:$PATH" \
    FAKE_NAK_STATE="$state" \
    FAKE_CURL_STATE="$curl_state" \
    FAKE_APK_SHA256="$expected_sha" \
    FAKE_APK_SIZE="$(wc -c < "$apk" | tr -d ' ')" \
    FAKE_SCENARIO="$scenario" \
    FAKE_SERVE_TYPE="$serve_type" \
    FAKE_SERVE_SCENARIO="$serve_scenario" \
    BLOSSOM_UPLOAD_BACKOFF_SECONDS=0 \
    BLOSSOM_UPLOAD_TIMEOUT_SECONDS=1 \
    "$uploader" <"$runner_stdin" >"$stdout" 2>"$stderr"
  status=$?
  set -e
}

assert_success_after_retry() {
  local scenario=$1
  run_uploader "$scenario"
  [[ "$status" == '0' ]]
  [[ "$(<"$stdout")" == "https://example.test/${expected_sha}.apk" ]]
  [[ "$(<"$state")" == '2' ]]
  if [[ "$(<"$stderr")" == *'test-secret-must-not-leak'* ]]; then
    printf 'upload secret leaked to stderr\n' >&2
    exit 1
  fi
}

run_uploader success
if [[ "$status" != '0' ]]; then
  cat "$stderr" >&2
  exit 1
fi
[[ "$status" == '0' ]]
[[ "$(<"$state")" == '1' ]]
printf 'ok - sends an explicit APK MIME type with scoped BUD-11 authorization\n'

assert_success_after_retry http-502
printf 'ok - retries an HTTP 502 and succeeds without leaking the upload secret\n'

assert_success_after_retry transport
printf 'ok - retries a transient connection reset\n'

assert_success_after_retry dialing-timeout
printf 'ok - retries a transient dialing timeout\n'

assert_success_after_retry eof
printf 'ok - retries a transient EOF\n'

assert_success_after_retry broken-pipe
printf 'ok - retries a transient broken pipe\n'

assert_success_after_retry timeout
printf 'ok - retries a bounded upload timeout\n'

assert_success_after_retry forced-timeout
printf 'ok - retries a timeout that requires SIGKILL escalation\n'

run_uploader self-sigkill
[[ "$status" == '137' ]]
[[ "$(<"$state")" == '1' ]]
[[ "$(<"$stderr")" != *'Transient Blossom upload failure'* ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - does not retry an unrelated SIGKILL\n'

run_uploader http-401
[[ "$status" != '0' ]]
[[ "$(<"$state")" == '1' ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - does not retry a permanent authentication failure\n'

run_uploader always-503
[[ "$status" != '0' ]]
[[ "$(<"$state")" == '3' ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - stops retrying after the finite attempt cap\n'

run_uploader nxdomain
[[ "$status" != '0' ]]
[[ "$(<"$state")" == '1' ]]
printf 'ok - does not retry a permanent DNS lookup failure\n'

run_uploader hash-mismatch
[[ "$status" != '0' ]]
[[ "$(<"$state")" == '1' ]]
[[ "$(<"$stderr")" == *'Blossom upload sha mismatch'* ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - fails closed on a returned SHA-256 mismatch\n'

run_uploader always-mime-mismatch
[[ "$status" != '0' ]]
[[ "$(<"$state")" == '1' ]]
[[ "$(<"$stderr")" == *'Blossom stored APK as unsupported MIME text/plain'* ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - fails closed immediately when an existing blob has the wrong MIME type\n'

run_uploader zip-upload-mime
[[ "$status" == '0' ]]
[[ "$(<"$state")" == '1' ]]
printf 'ok - accepts an APK content-sniffed as its ZIP container type\n'

run_uploader auth-failure
[[ "$status" != '0' ]]
[[ "$(<"$state")" == '0' ]]
[[ "$(<"$stderr")" == *'Failed to create Blossom upload authorization'* ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - fails closed when scoped authorization cannot be created\n'

run_uploader parameterized-upload-mime
[[ "$status" == '0' ]]
[[ "$(<"$state")" == '1' ]]
printf 'ok - accepts a case-insensitive parameterized upload MIME type\n'

for serve_type in \
  'application/vnd.android.package-archive' \
  ' Application/Vnd.Android.Package-Archive ; charset=binary' \
  'application/zip'; do
  run_uploader success "$serve_type"
  [[ "$status" == '0' ]]
  [[ "$(<"$state")" == '1' ]]
done
printf 'ok - accepts bare and parameterized HEAD Content-Type values\n'

for serve_scenario in transport mime-delay; do
  run_uploader success 'application/vnd.android.package-archive' "$serve_scenario"
  [[ "$status" == '0' ]]
  [[ "$(<"$state")" == '1' ]]
  [[ "$(<"$curl_state")" == '2' ]]
  [[ "$(<"$stderr")" == *'Transient Blossom serve verification failure'* ]]
done
printf 'ok - retries transient HEAD transport and propagation failures\n'

run_uploader success 'text/plain'
[[ "$status" != '0' ]]
[[ "$(<"$state")" == '1' ]]
[[ "$(<"$curl_state")" == '3' ]]
[[ "$(<"$stderr")" == *'Blossom serves '*' as unsupported APK MIME text/plain'* ]]
printf 'ok - fails closed on a wrong HEAD Content-Type\n'

for scenario in empty-output malformed-output; do
  run_uploader "$scenario"
  [[ "$status" != '0' ]]
  [[ "$(<"$state")" == '1' ]]
  [[ "$(<"$stderr")" == *'Blossom upload returned an invalid response'* ]]
  [[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
done
printf 'ok - fails closed on empty or malformed success output\n'

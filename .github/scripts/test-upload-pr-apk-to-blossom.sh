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
fake_nak="$tmp/nak"

cat > "$fake_nak" <<'FAKE_NAK'
#!/usr/bin/env bash
set -euo pipefail
cat >/dev/null
attempt=$(<"$FAKE_NAK_STATE")
attempt=$((attempt + 1))
printf '%s\n' "$attempt" > "$FAKE_NAK_STATE"
case "$FAKE_SCENARIO:$attempt" in
  http-502:1)
    printf "failed to upload to 'https://example.test': failed to upload: upload returned an error (502): bad gateway\n" >&2
    exit 123
    ;;
  transport:1)
    printf "failed to upload to 'https://example.test': Put https://example.test/upload: read: connection reset by peer\n" >&2
    exit 123
    ;;
  dialing-timeout:1)
    printf "failed to upload to 'https://example.test': failed to call upload: error when dialing 192.0.2.1:443: dialing to the given TCP address timed out\n" >&2
    exit 123
    ;;
  eof:1)
    printf 'EOF\n' >&2
    exit 123
    ;;
  broken-pipe:1)
    printf "failed to upload to 'https://example.test': Put https://example.test/upload: write: broken pipe\n" >&2
    exit 123
    ;;
  timeout:1)
    sleep 2
    ;;
  forced-timeout:1)
    trap '' TERM
    sleep 12
    ;;
  self-sigkill:*)
    printf 'timeout: sending signal KILL to command fake-nak\n' >&2
    kill -KILL "$$"
    ;;
  http-401:*)
    printf "failed to upload to 'https://example.test': failed to upload: upload returned an error (401): unauthorized\n" >&2
    exit 123
    ;;
  always-503:*)
    printf "failed to upload to 'https://example.test': failed to upload: upload returned an error (503): unavailable\n" >&2
    exit 123
    ;;
  hash-mismatch:*)
    printf '{"sha256":"%064d"}\n' 0
    exit 0
    ;;
  empty-output:*)
    exit 0
    ;;
  malformed-output:*)
    printf 'not-json\n'
    exit 0
    ;;
  nxdomain:*)
    printf "failed to upload to 'https://example.test': dial tcp: lookup example.test: no such host\n" >&2
    exit 123
    ;;
esac
printf '{"sha256":"%s"}\n' "$FAKE_APK_SHA256"
FAKE_NAK
chmod +x "$fake_nak"

run_uploader() {
  local scenario=$1
  printf '0\n' > "$state"
  stdout="$tmp/$scenario.stdout"
  stderr="$tmp/$scenario.stderr"
  set +e
  APK_PATH="$apk" \
    BLOSSOM_SERVER='https://example.test' \
    BLOSSOM_UPLOAD_NSEC='test-secret-must-not-leak' \
    NAK_BIN="$fake_nak" \
    FAKE_NAK_STATE="$state" \
    FAKE_APK_SHA256="$expected_sha" \
    FAKE_SCENARIO="$scenario" \
    BLOSSOM_UPLOAD_BACKOFF_SECONDS=0 \
    BLOSSOM_UPLOAD_TIMEOUT_SECONDS=1 \
    "$uploader" >"$stdout" 2>"$stderr"
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

for scenario in empty-output malformed-output; do
  run_uploader "$scenario"
  [[ "$status" != '0' ]]
  [[ "$(<"$state")" == '1' ]]
  [[ "$(<"$stderr")" == *'Blossom upload returned an invalid response'* ]]
  [[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
done
printf 'ok - fails closed on empty or malformed success output\n'

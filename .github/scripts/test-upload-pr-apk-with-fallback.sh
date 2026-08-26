#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
uploader="$script_dir/upload-pr-apk-with-fallback.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

apk="$tmp/preview.apk"
calls="$tmp/calls"
fake_uploader="$tmp/uploader"
printf 'test apk bytes\n' > "$apk"

cat > "$fake_uploader" <<'FAKE_UPLOADER'
#!/usr/bin/env bash
set -euo pipefail
: "${APK_PATH:?}"
: "${BLOSSOM_SERVER:?}"
: "${BLOSSOM_UPLOAD_NSEC:?}"
[[ -f "$APK_PATH" ]]
[[ "$BLOSSOM_UPLOAD_NSEC" == test-secret-must-not-leak ]]
printf '%s\n' "$BLOSSOM_SERVER" >> "$FAKE_CALLS"
case "$BLOSSOM_SERVER" in
  https://primary.example)
    if [[ "$FAKE_SCENARIO" == primary-success ]]; then
      printf '%s/test.apk\n' "$BLOSSOM_SERVER"
    else
      printf 'primary unavailable\n' >&2
      exit 28
    fi
    ;;
  https://fallback.example)
    if [[ "$FAKE_SCENARIO" == all-fail ]]; then
      printf 'fallback unavailable\n' >&2
      exit 56
    else
      printf '%s/test.apk\n' "$BLOSSOM_SERVER"
    fi
    ;;
  *)
    printf 'unexpected server: %s\n' "$BLOSSOM_SERVER" >&2
    exit 2
    ;;
esac
FAKE_UPLOADER
chmod +x "$fake_uploader"

run_uploader() {
  local scenario=$1
  local servers=${2:-'https://primary.example https://fallback.example'}
  : > "$calls"
  stdout="$tmp/$scenario.stdout"
  stderr="$tmp/$scenario.stderr"
  set +e
  APK_PATH="$apk" \
    BLOSSOM_SERVERS="$servers" \
    BLOSSOM_UPLOAD_NSEC='test-secret-must-not-leak' \
    BLOSSOM_UPLOADER="$fake_uploader" \
    FAKE_CALLS="$calls" \
    FAKE_SCENARIO="$scenario" \
    "$uploader" > "$stdout" 2> "$stderr"
  status=$?
  set -e
}

run_uploader fallback-success
[[ "$status" == 0 ]]
[[ "$(<"$stdout")" == 'https://fallback.example/test.apk' ]]
[[ "$(sed -n '1p' "$calls")" == 'https://primary.example' ]]
[[ "$(sed -n '2p' "$calls")" == 'https://fallback.example' ]]
[[ "$(wc -l < "$calls" | tr -d ' ')" == 2 ]]
[[ "$(<"$stderr")" == *'Blossom upload failed via https://primary.example'* ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - falls back after the primary Blossom upload fails\n'

run_uploader primary-success
[[ "$status" == 0 ]]
[[ "$(<"$stdout")" == 'https://primary.example/test.apk' ]]
[[ "$(<"$calls")" == 'https://primary.example' ]]
printf 'ok - stops after the primary Blossom upload succeeds\n'

run_uploader all-fail
[[ "$status" == 56 ]]
[[ "$(wc -l < "$calls" | tr -d ' ')" == 2 ]]
[[ "$(<"$stderr")" == *'Blossom upload failed via every configured origin.'* ]]
[[ "$(<"$stderr")" != *'test-secret-must-not-leak'* ]]
printf 'ok - fails closed after every Blossom origin fails\n'

run_uploader too-many 'https://1.example https://2.example https://3.example https://4.example https://5.example https://6.example'
[[ "$status" == 2 ]]
[[ ! -s "$calls" ]]
[[ "$(<"$stderr")" == *'BLOSSOM_SERVERS must contain between 1 and 5 origins'* ]]
printf 'ok - bounds the number of configured Blossom origins\n'

#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
publisher="$script_dir/publish-pr-apk-to-github-release.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

apk="$tmp/input.apk"
printf 'signed apk bytes' > "$apk"
fake_gh="$tmp/gh"
fake_curl="$tmp/curl"
gh_calls="$tmp/gh-calls"
curl_calls="$tmp/curl-calls"

cat > "$fake_gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_GH_CALLS"
[[ "$1 $2" == 'release view' || "$1 $2" == 'release create' || "$1 $2" == 'release upload' ]]
case "$1 $2" in
  'release view')
    if [[ "$FAKE_RELEASE_SCENARIO" == existing ]]; then
      exit 0
    fi
    if [[ "$FAKE_RELEASE_SCENARIO" == create-race ]] && \
      [[ "$(grep -c '^release view ' "$FAKE_GH_CALLS")" == 2 ]]; then
      exit 0
    fi
    exit 1
    ;;
  'release create')
    if [[ "$FAKE_RELEASE_SCENARIO" == create-race ]]; then
      exit 1
    fi
    exit 0
    ;;
  'release upload')
    [[ "$3" == android-pr-previews ]]
    [[ "$(basename "$4")" == whitenoise-pr-2775-stable.apk ]]
    cmp "$FAKE_APK_PATH" "$4"
    [[ "$*" == *'--repo marmot-protocol/whitenoise-android --clobber'* ]]
    ;;
esac
FAKE_GH
chmod +x "$fake_gh"

cat > "$fake_curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_CURL_CALLS"
attempt=$(wc -l < "$FAKE_CURL_CALLS" | tr -d ' ')
if [[ "$FAKE_HEADER_SCENARIO" == transport && "$attempt" == 1 ]]; then
  exit 7
fi
if [[ "$FAKE_HEADER_SCENARIO" == delayed && "$attempt" == 1 ]]; then
  type=application/zip
  disposition='inline; filename=""'
elif [[ "$FAKE_HEADER_SCENARIO" == wrong ]]; then
  type=application/zip
  disposition='inline; filename=""'
else
  type='application/vnd.android.package-archive'
  disposition='attachment; filename=whitenoise-pr-2775-stable.apk'
fi
printf 'HTTP/2 302\r\ncontent-type: text/html\r\n\r\n'
printf 'HTTP/2 200\r\ncontent-type: %s\r\ncontent-disposition: %s\r\n\r\n' "$type" "$disposition"
FAKE_CURL
chmod +x "$fake_curl"

run_publisher() {
  local release_scenario=$1 header_scenario=$2
  : > "$gh_calls"
  : > "$curl_calls"
  stdout="$tmp/$release_scenario-$header_scenario.stdout"
  stderr="$tmp/$release_scenario-$header_scenario.stderr"
  set +e
  APK_PATH="$apk" \
    GITHUB_REPOSITORY=marmot-protocol/whitenoise-android \
    GH_TOKEN=test-token \
    PR_NUMBER=2775 \
    PREVIEW_CHANNEL=stable \
    PR_PREVIEW_SERVE_BACKOFF_SECONDS=0 \
    FAKE_APK_PATH="$apk" \
    FAKE_RELEASE_SCENARIO="$release_scenario" \
    FAKE_HEADER_SCENARIO="$header_scenario" \
    FAKE_GH_CALLS="$gh_calls" \
    FAKE_CURL_CALLS="$curl_calls" \
    PATH="$tmp:$PATH" \
    "$publisher" >"$stdout" 2>"$stderr"
  status=$?
  set -e
}

run_publisher existing success
[[ "$status" == 0 ]]
[[ "$(<"$stdout")" == 'https://github.com/marmot-protocol/whitenoise-android/releases/download/android-pr-previews/whitenoise-pr-2775-stable.apk' ]]
[[ "$(grep -c '^release upload ' "$gh_calls")" == 1 ]]
[[ "$(grep -c '^release create ' "$gh_calls")" == 0 ]]
printf 'ok - replaces a stable per-PR asset and returns its browser URL\n'

run_publisher missing success
[[ "$status" == 0 ]]
[[ "$(grep -c '^release create ' "$gh_calls")" == 1 ]]
printf 'ok - creates the dedicated prerelease when it is missing\n'

run_publisher create-race success
[[ "$status" == 0 ]]
[[ "$(grep -c '^release create ' "$gh_calls")" == 1 ]]
[[ "$(grep -c '^release view ' "$gh_calls")" == 2 ]]
printf 'ok - tolerates another PR winning the release creation race\n'

for scenario in transport delayed; do
  run_publisher existing "$scenario"
  [[ "$status" == 0 ]]
  [[ "$(wc -l < "$curl_calls" | tr -d ' ')" == 2 ]]
done
printf 'ok - retries transient transport and header propagation failures\n'

run_publisher existing wrong
[[ "$status" != 0 ]]
[[ "$(wc -l < "$curl_calls" | tr -d ' ')" == 3 ]]
[[ "$(<"$stderr")" == *'Published APK is not browser-installable'* ]]
printf 'ok - rejects the inline ZIP response that broke tap-to-install\n'

set +e
APK_PATH="$apk" GITHUB_REPOSITORY=marmot-protocol/whitenoise-android GH_TOKEN=test \
  PR_NUMBER=2775 PREVIEW_CHANNEL=unknown PATH="$tmp:$PATH" "$publisher" >/dev/null 2>"$tmp/invalid.stderr"
invalid_status=$?
set -e
[[ "$invalid_status" == 2 ]]
[[ "$(<"$tmp/invalid.stderr")" == *'PREVIEW_CHANNEL must be stable or isolated'* ]]
printf 'ok - validates the asset identity inputs before publishing\n'

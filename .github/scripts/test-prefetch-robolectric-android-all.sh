#!/usr/bin/env bash
# Exercises scripts/prefetch-robolectric-android-all.sh against a local file:// repository.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_DIR/scripts/prefetch-robolectric-android-all.sh"
FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT

VERSION=16-robolectric-1-i7
JAR=android-all-instrumented-$VERSION.jar
remote="$FIXTURE/remote"
mkdir -p "$remote/org/robolectric/android-all-instrumented/$VERSION"
printf 'android runtime\n' >"$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR"
good_sha="$(sha512sum "$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR" | cut -d' ' -f1)"
printf 'robolectric = "4.17"\n' >"$FIXTURE/libs.versions.toml"

# Runs the prefetch with the given pin file into the given local repository.
run_prefetch() {
  ROBOLECTRIC_ANDROID_ALL_PINS="$1" ROBOLECTRIC_LOCAL_REPOSITORY="$2" \
    ROBOLECTRIC_VERSION_CATALOG="$FIXTURE/libs.versions.toml" \
    ROBOLECTRIC_ANDROID_ALL_REMOTE="file://$remote" bash "$SCRIPT"
}

# Fails the test with a message.
fail() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

printf '# comment\nrobolectric=4.17\n%s %s\n' "$VERSION" "$good_sha" >"$FIXTURE/good.pins"
local_repo="$FIXTURE/m2"
run_prefetch "$FIXTURE/good.pins" "$local_repo" | grep -Fq "$JAR: fetched" || fail 'jar was not fetched'
cmp -s "$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR" \
  "$local_repo/org/robolectric/android-all-instrumented/$VERSION/$JAR" || fail 'fetched jar differs'
run_prefetch "$FIXTURE/good.pins" "$local_repo" | grep -Fq "$JAR: already present" ||
  fail 'a verified jar was downloaded again'

printf 'stale\n' >"$local_repo/org/robolectric/android-all-instrumented/$VERSION/$JAR"
run_prefetch "$FIXTURE/good.pins" "$local_repo" | grep -Fq "$JAR: fetched" || fail 'a corrupt jar was kept'

printf 'robolectric=4.17\n%s %0128d\n' "$VERSION" 0 >"$FIXTURE/bad.pins"
if run_prefetch "$FIXTURE/bad.pins" "$FIXTURE/bad-m2" 2>/dev/null; then
  fail 'a jar with the wrong SHA-512 was accepted'
fi
[[ -z "$(find "$FIXTURE/bad-m2" -type f)" ]] || fail 'a rejected download was left behind'

printf 'robolectric=4.16\n%s %s\n' "$VERSION" "$good_sha" >"$FIXTURE/old.pins"
if run_prefetch "$FIXTURE/old.pins" "$FIXTURE/old-m2" 2>/dev/null; then
  fail 'pins for another Robolectric version were accepted'
fi

printf 'robolectric=4.17\n' >"$FIXTURE/empty.pins"
if run_prefetch "$FIXTURE/empty.pins" "$FIXTURE/empty-m2" 2>/dev/null; then
  fail 'an empty jar list was accepted'
fi


# Reject malformed/overflowing budgets before even reusing a verified cache.
for budget in 1 241 08 18446744073709551618; do
  if ROBOLECTRIC_PREFETCH_BUDGET_SECONDS="$budget" \
      run_prefetch "$FIXTURE/good.pins" "$local_repo" >/dev/null 2>&1; then
    fail 'invalid budget escaped the fixed maximum'
  fi
done
# A fake consumer transport simulates Retry-After/backoff without contacting any server.
mkdir -p "$FIXTURE/bin"
cat >"$FIXTURE/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$PREFETCH_CALLS"
output=""; url=""
while (($#)); do
  if [[ "$1" == --output ]]; then output="$2"; shift 2; else url="$1"; shift; fi
done
printf 'partial must never be admitted\n' >"$output"
[[ "$url" == "$PREFETCH_EXPECTED_REMOTE/"* ]] || exit 97
if [[ "$PREFETCH_MODE" == cumulative && "$url" == "$PREFETCH_GOOD_URL" ]]; then
  sleep 1
  cp -- "$PREFETCH_GOOD_JAR" "$output"
  exit 0
fi
if [[ "$PREFETCH_MODE" == stall || "$PREFETCH_MODE" == cumulative ]]; then
  # Model a retry sleep/transfer that ignores graceful cancellation.
  trap '' TERM
  sleep 30 &
  child=$!
  printf '%s\n%s\n' "$$" "$child" >>"$PREFETCH_PIDS"
  printf 'HTTP429 Retry-After3600 simulated backoff\n' >&2
  wait "$child"
else
  sleep 1
  exit 22
fi
FAKE_CURL
chmod +x "$FIXTURE/bin/curl"
export PREFETCH_CALLS="$FIXTURE/calls" PREFETCH_EXPECTED_REMOTE="https://custom.invalid/only"
export PREFETCH_PIDS="$FIXTURE/pids"
assert_transport_stopped() {
  while read -r pid; do
    if kill -0 "$pid" 2>/dev/null; then
      state="$(ps -o stat= -p "$pid")"
      [[ "$state" == Z* ]] || fail 'timed transport or its descendant is still running'
    fi
  done <"$PREFETCH_PIDS"
}
run_fake() {
  PATH="$FIXTURE/bin:$PATH" ROBOLECTRIC_PREFETCH_BUDGET_SECONDS=3 \
    ROBOLECTRIC_ANDROID_ALL_PINS="$1" ROBOLECTRIC_LOCAL_REPOSITORY="$2" \
    ROBOLECTRIC_VERSION_CATALOG="$FIXTURE/libs.versions.toml" \
    ROBOLECTRIC_ANDROID_ALL_REMOTE="$PREFETCH_EXPECTED_REMOTE" bash "$SCRIPT"
}
# A verified jar must not invoke transport, even when that transport would stall.
export PREFETCH_MODE=stall
: >"$PREFETCH_CALLS"
run_fake "$FIXTURE/good.pins" "$local_repo" >/dev/null
[[ ! -s "$PREFETCH_CALLS" ]] || fail 'verified cache invoked transport'
started=$SECONDS
if run_fake "$FIXTURE/good.pins" "$FIXTURE/stall-m2" >/dev/null 2>&1; then
  fail 'stalled429 retry consumer succeeded'
fi
((SECONDS - started <= 5)) || fail 'hard cumulative budget was exceeded'
[[ "$(wc -l <"$PREFETCH_CALLS")" -eq 1 ]] || fail 'custom remote was retried outside the timed curl'
[[ -z "$(find "$FIXTURE/stall-m2" -type f)" ]] || fail 'stalled partial was retained/admitted'
[[ -z "$(find "$FIXTURE/stall-m2" -name '.prefetch.*')" ]] || fail 'owned partial directory was retained'
assert_transport_stopped
# The first successful jar and the second stalled429 share exactly one deadline.
export PREFETCH_MODE=cumulative
export PREFETCH_GOOD_JAR="$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR"
export PREFETCH_GOOD_URL="$PREFETCH_EXPECTED_REMOTE/org/robolectric/android-all-instrumented/$VERSION/$JAR"
printf 'robolectric=4.17\n%s %s\nother-version %s\nthird-version %s\n' \
  "$VERSION" "$good_sha" "$good_sha" "$good_sha" >"$FIXTURE/three.pins"
: >"$PREFETCH_CALLS"
: >"$PREFETCH_PIDS"
started=$SECONDS
if run_fake "$FIXTURE/three.pins" "$FIXTURE/cumulative-m2" >/dev/null 2>&1; then
  fail 'cumulative stalled transport succeeded'
fi
((SECONDS - started <= 5)) || fail 'downloads reset the cumulative deadline'
[[ "$(wc -l <"$PREFETCH_CALLS")" -eq 2 ]] || fail 'deadline admitted a third request'
cmp -s "$PREFETCH_GOOD_JAR" \
  "$FIXTURE/cumulative-m2/org/robolectric/android-all-instrumented/$VERSION/$JAR" ||
  fail 'already verified artifact was not preserved'
[[ "$(find "$FIXTURE/cumulative-m2" -type f | wc -l)" -eq 1 ]] || fail 'partial admitted after cumulative timeout'
assert_transport_stopped
# A failed first artifact prevents starting later artifacts; no endpoint fallback.
export PREFETCH_MODE=fail
printf 'robolectric=4.17\n%s %s\nother-version %s\n' "$VERSION" "$good_sha" "$good_sha" >"$FIXTURE/two.pins"
: >"$PREFETCH_CALLS"
if run_fake "$FIXTURE/two.pins" "$FIXTURE/fail-m2" >/dev/null 2>&1; then
  fail 'failed custom source was accepted'
fi
[[ "$(wc -l <"$PREFETCH_CALLS")" -eq 1 ]] || fail 'failure escaped the exclusive source or advanced jars'
[[ -z "$(find "$FIXTURE/fail-m2" -type f)" ]] || fail 'failed partial was retained'

printf 'test-prefetch-robolectric-android-all.sh passed\n'

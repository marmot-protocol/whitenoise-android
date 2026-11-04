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

printf 'test-prefetch-robolectric-android-all.sh passed\n'

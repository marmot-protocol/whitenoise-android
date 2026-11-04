#!/usr/bin/env bash
# Puts Robolectric's runtime Android jars into the local Maven repository before the
# unit tests start. Robolectric uses a jar it finds there and otherwise downloads it
# inside the test JVMs with no retry, so one dropped connection fails every test class
# on that SDK. This download retries and checks each jar against its pinned SHA-512.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINS="${ROBOLECTRIC_ANDROID_ALL_PINS:-$REPO_DIR/scripts/robolectric-android-all.sha512}"
CATALOG="${ROBOLECTRIC_VERSION_CATALOG:-$REPO_DIR/gradle/libs.versions.toml}"
REMOTE="${ROBOLECTRIC_ANDROID_ALL_REMOTE:-https://repo1.maven.org/maven2}"
LOCAL_REPO="${ROBOLECTRIC_LOCAL_REPOSITORY:-$HOME/.m2/repository}"
ARTIFACT_PATH=org/robolectric/android-all-instrumented

# Prints the SHA-512 of a file.
sha512_of() {
  sha512sum -- "$1" | cut -d' ' -f1
}

# Fails unless the pinned list was written for the Robolectric version the build uses.
check_robolectric_version() {
  local pinned catalog
  pinned="$(sed -n 's/^robolectric=//p' "$PINS")"
  catalog="$(sed -n 's/^robolectric = "\(.*\)"$/\1/p' "$CATALOG")"
  if [[ -z "$pinned" || "$pinned" != "$catalog" ]]; then
    printf 'error: %s pins Robolectric %s but the build uses %s; update the jar list\n' \
      "$PINS" "${pinned:-none}" "${catalog:-none}" >&2
    return 1
  fi
}

# Downloads one jar unless a verified copy is already in the local repository.
fetch_jar() {
  local version="$1" expected="$2"
  local name="android-all-instrumented-$version.jar"
  local dir="$LOCAL_REPO/$ARTIFACT_PATH/$version"
  local partial="$dir/$name.part"

  if [[ -f "$dir/$name" && "$(sha512_of "$dir/$name")" == "$expected" ]]; then
    printf '%s: already present\n' "$name"
    return 0
  fi
  mkdir -p "$dir"
  # A stalled transfer (under 1 MiB/s for 30 s) or a slow attempt fails so the next retry starts.
  curl --fail --silent --show-error --location --retry 5 --retry-all-errors --retry-delay 5 \
    --connect-timeout 20 --speed-limit 1048576 --speed-time 30 --max-time 300 \
    --output "$partial" "$REMOTE/$ARTIFACT_PATH/$version/$name"
  if [[ "$(sha512_of "$partial")" != "$expected" ]]; then
    rm -f -- "$partial"
    printf 'error: %s does not match its pinned SHA-512\n' "$name" >&2
    return 1
  fi
  mv -f -- "$partial" "$dir/$name"
  printf '%s: fetched\n' "$name"
}

# Fetches every pinned jar.
main() {
  local version expected count=0
  check_robolectric_version
  while read -r version expected; do
    [[ -n "$version" && "$version" != \#* && "$version" != robolectric=* ]] || continue
    fetch_jar "$version" "$expected"
    count=$((count + 1))
  done <"$PINS"
  if ((count == 0)); then
    printf 'error: %s lists no jars\n' "$PINS" >&2
    return 1
  fi
}

main "$@"

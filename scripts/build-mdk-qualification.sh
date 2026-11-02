#!/usr/bin/env bash
# Build both published runtime generations on the hosted runner with one disposable debug signer.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Hosted CI required' >&2; exit 2; }
root="$PWD"
export WHITENOISE_MARMOTKIT_CACHE_DIR="$RUNNER_TEMP/mdk-qualification-cache"
reports="$root/build/mdk-qualification"
mkdir -p "$reports" "$root/build/mdk-pair/baseline" "$root/build/mdk-pair/candidate"
git fetch origin master --no-tags
baseline=$(git rev-parse origin/master)
printf '%s\n' "$baseline" > "$reports/baseline-source.txt"
git show "$baseline:app/src/main/marmotkit/MARMOT_VERSION" > "$reports/baseline-MARMOT_VERSION"
cp app/src/main/marmotkit/MARMOT_VERSION "$reports/candidate-MARMOT_VERSION"
# Fail closed if master has moved to a runtime outside this reviewed upgrade pair.
grep -qx 'mdk-sha=3fb4b4759ac676b0c21c4c3527074866c9fa4c89' "$reports/baseline-MARMOT_VERSION"
git worktree add --detach "$RUNNER_TEMP/mdk-baseline" "$baseline"
python3 scripts/stage_mdk_baseline_fixture.py "$RUNNER_TEMP/mdk-baseline"
for role in baseline candidate; do
  checkout="$root"
  [[ "$role" != baseline ]] || checkout="$RUNNER_TEMP/mdk-baseline"
  (
    cd "$checkout"
    ./gradlew --init-script "$root/scripts/mdk-qualification.init.gradle" \
      -Pandroid.injected.build.abi=x86_64 -Pandroid.injected.testOnly=true \
      :app:assembleDevZapstoreDebug :app:assembleDevZapstoreDebugAndroidTest --no-daemon --stacktrace
    # Single-ABI (IDE-style) assembly uses intermediates for app and test APKs.
    mapfile -t app_apks < <(
      for base in app/build/outputs/apk app/build/intermediates/apk; do
        [[ -d "$base/devZapstore/debug" ]] || continue
        find "$base/devZapstore/debug" -maxdepth 1 -type f -name '*x86_64*.apk'
      done
    )
    mapfile -t test_apks < <(
      for base in app/build/outputs/apk app/build/intermediates/apk; do
        [[ -d "$base/androidTest/devZapstore/debug" ]] || continue
        find "$base/androidTest/devZapstore/debug" -maxdepth 1 -type f -name '*.apk'
      done
    )
    if [[ ${#app_apks[@]} != 1 || ${#test_apks[@]} != 1 ]]; then
      printf 'Expected one app and one test APK; found %d and %d\n' "${#app_apks[@]}" "${#test_apks[@]}" >&2
      find app/build -name '*.apk' -print >&2
      exit 1
    fi
    cp "${app_apks[0]}" "$root/build/mdk-pair/$role/app.apk"
    cp "${test_apks[0]}" "$root/build/mdk-pair/$role/test.apk"
  )
done
sha256sum build/mdk-pair/{baseline,candidate}/*.apk > "$reports/APK-SHA256SUMS"
git rev-parse HEAD > "$reports/candidate-source.txt"
python3 scripts/verify_mdk_qualification_pair.py

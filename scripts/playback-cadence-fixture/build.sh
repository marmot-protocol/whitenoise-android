#!/usr/bin/env bash
set -euo pipefail

source_head=86f8fd2244d1c0fecab41c791fc5cb06fb838353
fixture=scripts/playback-cadence-fixture
package=dev.ipf.whitenoise.android.smoothness.finalreserve
output=build/playback-cadence-apks

git merge-base --is-ancestor "$source_head" HEAD
while IFS= read -r path; do
    case "$path" in
        .github/workflows/android-instrumented.yml|"$fixture"/*) ;;
        *) printf 'Unexpected source delta: %s\n' "$path" >&2; exit 1 ;;
    esac
done < <(git diff --name-only "$source_head" HEAD)
(cd "$fixture" && sha256sum --check SHA256SUMS)
./gradlew --no-daemon --console=plain --stacktrace \
    -I "$fixture/climb-init.gradle" -PclimbFixture="$PWD/$fixture/fixture" \
    -PclimbApplicationId="$package" -Pandroid.injected.build.abi=arm64-v8a \
    :app:assembleDevZapstoreNonMinifiedRelease \
    :app:assembleDevZapstoreNonMinifiedReleaseAndroidTest

mkdir "$output"
app=app/build/intermediates/apk/devZapstore/nonMinifiedRelease
test=app/build/intermediates/apk/androidTest/devZapstore/nonMinifiedRelease
app_name=$(jq -er --arg id "$package" '
    select(.applicationId == $id) | .elements[]
    | select(any(.filters[]; .filterType == "ABI" and .value == "arm64-v8a"))
    | .outputFile
' "$app/output-metadata.json")
test_name=$(jq -er --arg id "$package.test" '
    select(.applicationId == $id and (.elements | length) == 1) | .elements[0].outputFile
' "$test/output-metadata.json")
cp "$app/$app_name" "$test/$test_name" "$output/"
cp "$fixture/SHA256SUMS" "$output/fixture-sha256sums.txt"
printf '%s\n' "$source_head" > "$output/source-sha.txt"
git rev-parse HEAD > "$output/fixture-revision.txt"
(cd "$output" && sha256sum -- *.apk > sha256sums.txt)

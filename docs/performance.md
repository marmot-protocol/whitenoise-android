# Android performance toolkit

This toolkit measures the optimized app rather than a debuggable Compose build.
The Baseline Profile plugin derives two release-like variants from `release`:

- `devZapstoreBenchmarkRelease`: minified, non-debuggable, profileable; used for Macrobenchmark.
- `devZapstoreNonMinifiedRelease`: non-minified, non-debuggable, profileable; used to collect profiles.

Both use the Android debug key and the existing dev application ID
(`dev.ipf.whitenoise.android.dev`). They cannot weaken or replace staging or
production release signing. Direct `adb install -r` swaps preserve the dedicated
fixture account's MDK SQLite state; the state-preserving runner below uses that
path instead of AGP's connected-test install/teardown lifecycle.

The structure follows the Android team's
[Now in Android benchmark module](https://github.com/android/nowinandroid/tree/main/benchmarks)
and [Macrobenchmark sample](https://github.com/android/performance-samples/tree/main/MacrobenchmarkSample).

## Host prerequisites

Install the Android SDK (including Platform Tools for `adb`), `jq`, and ripgrep
(`rg`). The Baseline Profile verifier also needs either `apkanalyzer` from the
Android SDK Command-Line Tools or `unzip` as a fallback. The scripts check these
commands up front and report the missing dependency.

## Prepare a physical-device fixture

Use a dedicated API 34+ device with animations disabled and a stable power and
thermal state. Emulator results are useful only as smoke tests; do not publish
them as performance numbers.

1. Install the normal dev app: `./gradlew :app:installDevZapstoreDebug`.
2. Sign in with a non-production test identity.
3. Create or receive a group with at least two members. Record its exact display
   name as `GROUP_NAME`.
4. For the one-shot invite benchmark, arrange a pending invitation and record
   its exact display name as `INVITE_NAME`. Re-create this fixture before each
   invite benchmark run because acceptance is intentionally irreversible.
5. Close any system overlays and keep the device awake and unlocked.

The journeys never clear package data. They use real UI actions and the real MDK
store; no Android-side protocol cache or fake performance data is introduced.
Use a dedicated test identity: AGP removes the temporary dev target after
`generateBaselineProfile`, so the app must be reinstalled and signed in again
before running measurements. The synced group remains available to the account.

## Run Macrobenchmarks

Run startup plus the repeatable group-open journey without allowing AGP to
remove the authenticated target package:

```bash
ANDROID_SERIAL=<device-serial> \
  scripts/run-performance-benchmarks.sh "$GROUP_NAME"
```

The script builds the normal dev and release-like APKs, replaces the dev app in
place, invokes only the startup and group-open before/after methods, and pulls
JSON plus Perfetto output into `benchmark/build/outputs/manual/`. Its exit trap
restores the normal dev debug APK and uninstalls the self-instrumenting benchmark
package even when a benchmark fails; the authenticated app data remains intact.

For a focused rerun, set AndroidJUnitRunner's comma-separated class filter via
`BENCHMARK_CLASS_FILTER`; the script still uses the same state-preserving path.

To measure group creation separately, use the connected task with an explicit
mutation argument:

```bash
./gradlew :benchmark:connectedDevZapstoreBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#createGroupConversationOpen \
  -Pandroid.testInstrumentationRunnerArguments.createdGroupPrefix="Benchmark group"
```

`createdGroupPrefix` is an explicit mutation guard: the creation benchmark is
skipped unless it is supplied, because each of its three measured iterations
creates and syncs a persistent MLS group. Omit it when measuring only startup
and the repeatable group-open journey.

Run the one-shot invite journey by filtering to its test method:

```bash
./gradlew :benchmark:connectedDevZapstoreBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#acceptInviteConversationReady \
  -Pandroid.testInstrumentationRunnerArguments.inviteName="$INVITE_NAME"
```

`StartupBenchmark` reports `timeToInitialDisplayMs` and frame timing with no
compilation and with the packaged Baseline Profile. `GroupFlowsBenchmark`
reports `journeyDurationMs`, frame timing, and a Perfetto trace for group open →
members visible, group creation → conversation ready, and invite acceptance →
conversation ready.

The state-preserving script copies results and `.perfetto-trace` files under:

```text
benchmark/build/outputs/manual/<UTC timestamp>/
```

`./gradlew :benchmark:connectedCheck` remains available for an ephemeral fixture
or CI device. Supplying `groupName` is required for authenticated group tests;
group creation and invite acceptance also require their explicit arguments.
Tests whose fixture or mutation argument is missing are reported as skipped.
The connected task may uninstall the target package during teardown, so prefer
the script for a local stateful fixture.

## Generate and package the Baseline Profile

Generate startup, chat-list, group-open, and member-roster rules on the prepared
device. Only launch-to-chat-list rules enter the Startup Profile; the broader
group and roster journey remains in the Baseline Profile so it cannot crowd
startup code out of the primary DEX:

```bash
./gradlew :app:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.groupName="$GROUP_NAME"
```

The generated profile is merged into and committed from
`app/src/main/generated/baselineProfiles/`. It is consumed by every supported
release variant; generation does not run implicitly during ordinary release
assembly. AGP removes the temporary dev app after collection; reinstall the
normal dev APK and sign the fixture back in before running the measurement
script.

Build a release-like APK and verify both compiled profile assets:

```bash
./gradlew :app:assembleDevZapstoreBenchmarkRelease
bash scripts/verify-baseline-profile.sh \
  app/build/outputs/apk/devZapstore/benchmarkRelease/app-dev-zapstore-universal-benchmarkRelease.apk
```

The verifier uses `apkanalyzer` when available and falls back to the ZIP table.
For a signed staging/production APK, pass that APK path to the same script.

## Compose compiler reports

Generate the same optimized staging report that CI uploads:

```bash
./gradlew :app:compileStagingZapstoreReleaseKotlin \
  -Pwhitenoise.enableComposeCompilerReports=true
```

Outputs land in `app/build/compose-metrics/` and
`app/build/compose-reports/`. CI publishes them as the
`compose-compiler-reports` artifact for 14 days.

## PR measurement table

Use one physical device and unchanged fixture for both runs. Report medians and
the benchmark JSON artifact, and link the relevant traces.

| Journey | No compilation median | Baseline Profile median | Delta |
| --- | ---: | ---: | ---: |
| Cold startup → chat list | _ms_ | _ms_ | _%_ |
| Open group → members visible | _ms_ | _ms_ | _%_ |

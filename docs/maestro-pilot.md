# On-demand Maestro onboarding pilot

This pilot checks an installed White Noise dev APK: welcome → Sign In →
private-key screen → Android Back → welcome. It enters no key, creates no
account and uses no relays. The disposable API 34 x86_64 emulator runs offline
with runtime permissions denied. Each journey clears only that emulator's dev
app data and declines the optional diagnostics sheet without enabling sharing.

Use this to try installed-app UI automation before adding more journeys. It is
manually requested, is not a required PR check and has no automatic Maestro
trigger. Normal instrumented tests remain unchanged. There is no Maestro Cloud
account, API key, shared-phone access or local app build.

## Select an APK already built by CI

Wait for a successful **Android CI** run on the desired source commit. Its
existing Baseline Profile job already builds a universal dev benchmark APK.
The job retains that same APK as
`maestro-dev-apk-SOURCE_SHA-RUN_ID-RUN_ATTEMPT` for seven days, with its checksum
and source/checkout/run provenance. Retention adds an upload, not compilation.
No signed ARM64 preview can substitute for this x86_64-compatible artifact.

Copy the full source SHA and numeric artifact ID from that run. The artifact
link has the form `.../actions/runs/RUN_ID/artifacts/ARTIFACT_ID`. The run must be
a successful internal PR or master-push run of `android-ci.yml`; fork artifacts,
expired artifacts and outputs from other workflows are rejected. When CI uses
a PR merge checkout, the provenance records both the PR source and integration
checkout SHA. The pilot separately records its own flow revision.

## Request one journey

In **Actions → Android Instrumented Tests → Run workflow**, select the flow
branch, enable `maestro_pilot`, and fill `maestro_artifact_id` and
`maestro_source_sha`. Leave the demo/provider options off. Keep repetitions at
`1` and negative control off.

The equivalent CLI request is:

```sh
gh workflow run android-instrumented.yml \
  --repo marmot-protocol/whitenoise-android --ref FLOW_BRANCH \
  -f maestro_pilot=true \
  -f maestro_artifact_id=ARTIFACT_ID \
  -f maestro_source_sha=FULL_SOURCE_SHA \
  -f maestro_repetitions=1
```

Hermes uses its maintained authenticated `gh` wrapper with
`HERMES_GH_NO_CACHE=1`. The existing registered workflow permits a trial on a
candidate branch before the pilot lands on master. Verify that the dispatched
run resolves to the intended flow commit.

Selecting Maestro skips all normal Gradle and connected-test jobs in that
dispatch. The pilot validates source/run provenance, GitHub's archive digest,
the APK checksum, native x86_64 ELF payload, application ID and minimum SDK
before installing it. It uses checksummed Maestro CLI 2.11.0 and the existing
SHA-pinned emulator runner. Its token has read-only permissions and is exposed
only to metadata/download steps, not the emulator/test step.

## Preserve spare CI capacity

Request a pilot after normal CI finishes. A bounded check rejects other queued
or active repository runs before setup and rechecks just before emulator boot.
It fails closed when metadata is unavailable and never waits in a polling loop
or cancels another run. Pilot dispatches have a separate concurrency group with
cancellation disabled. This check detects existing work; it cannot reserve
capacity against CI that starts later. The only excluded workflow is the
lightweight PR screenshot-description updater.

If the capacity check fails, reuse the same artifact in a new manual request
after CI becomes idle. Do not rebuild the app or make an empty commit.

## Repetition, failure proof and results

Set `maestro_repetitions` to `20` to execute 20 clean journeys in one boot and one
CLI invocation. The emulator and Maestro driver stay warm; app state is fresh
for each journey. This checks consistency rather than production performance.

To check failure reporting, enable `maestro_negative_control`. An additional
case first executes the real journey, then asserts an impossible label. **That
job must fail**. A driver/setup error does not prove the negative assertion ran;
inspect JUnit and the failing assertion in Maestro's debug output.

The `maestro-pilot-RUN_ID-RUN_ATTEMPT` result artifact retains JUnit, Maestro
debug output, screenshots, bounded emulator logs, APK identity, source/run
provenance and timestamps on success or failure. Secure app screens may produce
blank screenshots; their accessibility assertions must still execute. Setup
failures can have partial evidence and no JUnit report, which is not a pass.

`timings.env` separates `setup_started_at`, `emulator_ready_at`,
`test_started_at` and `finished_at`; JUnit provides the actual case durations.
Compare emulator/setup cost with warm journey time before expanding coverage.
No speed claim is established by adding this workflow alone.

Stop requesting the optional job to disable the pilot immediately. Removing
the manual mode and retained artifact through a reviewed change restores the
previous tooling; app code and installed user data are unaffected.

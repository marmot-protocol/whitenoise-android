# On-demand Maestro onboarding tests

This pilot checks an installed White Noise dev APK: welcome → Sign In →
private-key screen → Android Back → welcome. It enters no key, creates no
account and uses no relays. The disposable API 34 x86_64 emulator runs offline
with runtime permissions denied. Each journey clears only that emulator's dev
app data and declines the optional diagnostics sheet without enabling sharing.
Before launch, the runner applies airplane mode through Android's connectivity
service, disables Wi-Fi and mobile data, and requires no active default network.
The bounded check retains its connectivity dump as `network-state.txt`.

Select the original `onboarding` journey or the six-case `offline` suite. It is
manually requested, is not a required PR check and has no automatic Maestro
trigger. Normal instrumented tests remain unchanged. There is no Maestro Cloud
account, API key, shared-phone access or local app build.

## Offline coverage and limits

Each allowlisted flow clears only the disposable dev app state, denies permissions and dismisses the known optional diagnostics sheet. Case names and screenshots are unique across repetitions. `suite-manifest.json` records the exact selected cases and their partial manual-checklist mappings; `suite-results.json` reconciles actual JUnit against that selection. Missing, extra, duplicated, skipped or failed cases cannot be reported as passing. The intentional negative control is recorded separately and still leaves the job failed.

| Flow | Assertions | Partial checklist coverage |
|---|---|---|
| `onboarding` | Welcome → private-key screen → Android Back → Welcome | `ONB-001`, `ONB-004` |
| `signin-invalid` | Empty action disabled; malformed text and malformed nsec input rejected; toolbar Back and reopen | `ONB-005` |
| `signin-public` | Synthetic public npub rejected with the secret-key-required message; Android Back | `ONB-005` |
| `signin-back` | Amber action absent; toolbar and Android Back work after typing | `ONB-004`, `ONB-010` |
| `signup-cancel` | Synthetic Name/About edits; cancellation and reopening | `ONB-002` |
| `signup-offline-retry` | Offline notice, Retry, retained edits, cancellation and reopening | `ONB-002` |

The malformed nsec fixture has invalid shape and checksum; it never enters native login. These checks do **not** validate checksum-only rejection of an otherwise shaped secret key, import a secret, create an account, establish native account counts, or prove peer delivery. The public fixture encodes synthetic zero public bytes and is not an account credential. Real signer, authenticated-account, relay, layout, accessibility, device and release checks remain separate.

The existing test tags are exposed by the retained benchmark APK. [Maestro state selectors](https://docs.maestro.dev/reference/selectors/state-selectors) check enabled state; [hideKeyboard](https://docs.maestro.dev/reference/commands-available/hidekeyboard) dismisses the IME before the separate navigation assertion. No fixed sleeps or coordinate taps are needed.

## What to add next

First require the offline suite and its intentional assertion failure to produce complete hosted evidence. Then add a separate opt-in prepared-account suite with disposable test identities, explicit setup/reset, no real keys in YAML or logs, and controlled relay prerequisites. Start with opening an existing conversation, draft entry/cancellation and settings navigation. Only after that fixture is reliable add two-peer messaging, media and lifecycle checks. Keep normal PR CI and required native integration checks unchanged; use the optional suite for focused checks when its surfaces change.

A failed flow is a failure to investigate, not permission to loosen assertions. Inspect the hierarchy/debug output and determine whether the APK behavior regressed or a selector is wrong. Reuse the same verified APK for tooling-only corrections; obtain a new producer APK when application behavior changes.

## Select an APK already built by CI

Wait for a successful **Android CI** run on the desired source commit. Its
existing Baseline Profile job already builds a universal dev benchmark APK.
The job retains that same APK as
`maestro-dev-apk-SOURCE_SHA-RUN_ID-RUN_ATTEMPT` for seven days, with its checksum
and source/checkout/run provenance. Retention adds an upload, not compilation.
No signed ARM64 preview can substitute for this x86_64-compatible artifact.
Staging and retention are non-blocking steps after required Baseline Profile
verification. An upload failure leaves normal CI green but no usable pilot APK.
Only internal PR and master-push runs retain these APKs; scheduled, manual and
fork runs skip retention.

Copy the full source SHA and numeric artifact ID from that run. The artifact
link has the form `.../actions/runs/RUN_ID/artifacts/ARTIFACT_ID`. The run must be
a successful internal PR or master-push run of `android-ci.yml`; fork artifacts,
expired artifacts and outputs from other workflows are rejected. When CI uses
a PR merge checkout, the provenance records both the PR source and integration
checkout SHA. The pilot separately records its own flow revision.
The artifact must belong to the producer's current successful run attempt.
Re-running only failed jobs can leave an older APK that is rejected; choose a
successful attempt that also ran the Baseline Profile job.

## Select coverage and request a run

In **Actions → Android Instrumented Tests → Run workflow**, select the flow
branch, enable `maestro_pilot`, and fill `maestro_artifact_id` and
`maestro_source_sha`. Leave the demo/provider options off. Select `maestro_suite=offline` for the expanded checks, or leave `onboarding` selected for the original navigation check. Keep repetitions at `1` and negative control off for the first run.

The equivalent CLI request is:

```sh
gh workflow run android-instrumented.yml \
  --repo marmot-protocol/whitenoise-android --ref FLOW_BRANCH \
  -f maestro_pilot=true \
  -f maestro_artifact_id=ARTIFACT_ID \
  -f maestro_source_sha=FULL_SOURCE_SHA \
  -f maestro_suite=offline \
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

## Run alongside normal CI

The pilot can start while other repository CI is queued or running. It reuses
an existing APK and occupies one GitHub-hosted runner for at most 15 minutes;
this can affect runner queue time. It never cancels another workflow or waits
for the whole repository to become idle.

Pilot dispatches have a separate concurrency group with cancellation disabled.
The group protects one active pilot but keeps only one pending dispatch:
another dispatch can replace that pending run. Request one at a time. Normal
push and PR checks retain their existing concurrency and execution behavior.

## Repetition, failure proof and results

The original `onboarding` suite permits 1–20 repetitions. The `offline` suite permits 1–3 repetitions: six distinct cases run once, twice or three times (6, 12 or 18 journeys). A request exceeding 20 positive journeys fails before APK download or emulator setup. All cases share one boot and CLI invocation. The emulator and Maestro driver stay warm; app state is fresh
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

Each case stops the previous app process before clearing app data. The Maestro
command has a ten-minute deadline, leaving time within the fifteen-minute job
for bounded log capture and diagnostic upload. A timeout is incomplete evidence,
never a passing suite or a verified negative control.

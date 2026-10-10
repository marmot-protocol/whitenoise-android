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

## Local WNPerf device diagnostics

Debug, PR-preview, and staging builds expose a **Performance logs** switch under
**Settings → Developer → Diagnostics**. It is off by default. Enabling it writes
only the closed `WNPerf` timing schema to Logcat for up to 30 minutes; disabling
it stops new events immediately. The session and its process-local counters are
never persisted, so a process restart also disables ordinary device collection.
The benchmark-selector build is the one exception: choosing that purpose-built
local variant is the runner's explicit opt-in before its cold-start measurement.

### Member activity presentation

`op=group_membership_pending` measures one local add/remove request's `accepted`
phase to its first uncovered transcript draw (`first_local_frame`).
`op=group_membership_projection` separately measures arrival of a new live native
member event (`timeline_subscription_received`, before projection application) to
its first uncovered draw. The latter is not correlated with a local request:
native group events have their own canonical identities and ordering.
Initial pages, older-history loads and snapshot refreshes are excluded. These
are presentation timings, not proof of relay acknowledgement or Welcome delivery.
Hidden reveal frames, prefetched measurements and fully covered rows do not
complete either timing. Request/row keys remain in controller-local memory;
only closed categories, opaque diagnostic counters and timing values are emitted
through the existing opt-in bounded session. Projection traces are capped at 32.

### Conversation history pages

`op=chat_history_page` covers one page of history in either direction: older,
the operation behind a report that scrolling up is slow, and newer, the page that
brings a saturated window back toward the live tail. Its phases are, in order:

| Phase | Layer | What it measures |
| --- | --- | --- |
| `page_anchor` | `ffi` | Reporting the reader's oldest visible row as the window anchor. Older pages only; absent when the row is not one MDK retains. |
| `page_window` | `ffi` | The window command itself, including any wait for a not-ready window. |
| `page_apply` | `android` | Folding the returned window into the timeline; `count` is the rows it carried. |
| `page_complete` | `android` | The whole page. `result=failure` means the engine did not answer — a deadline or an exhausted not-ready budget — and the reader was left a retry row. `result=dropped` means the page was cancelled before it finished, for example by the screen leaving or the subscription being replaced. |

A `page_window` far larger than `page_apply` is the engine taking time to reach
the request, not the app taking time to render it. A `page_anchor` with no
`page_complete` after it is a page still waiting on the engine: a cancelled page
closes as `dropped`, and a page the engine never answers closes as `failure`
when the window deadline passes.

The same page is also visible in a Perfetto trace as async slices under
`WhiteNoise.conversation.page.*` — `window`, `prepare` (the off-main-thread row
preparation) and `apply` (preparation plus the main-thread commit); the
return-to-latest and exact-message jump commands emit the same `window` and
`apply` slices, so a jump's window swap is counted alongside pages — together
with three zero-length event slices the conversation screen emits:
`edgeStop` when the list comes to rest on its oldest row with more history
behind it and no page landing, `runwayKept` when a page lands while the reader
still has rows before the previous edge, and `edgeReached` when the previous
edge was already on screen. These feed the paging Macrobenchmark below.

To validate a slow journey on a staging device, enable the switch, reproduce one
operation, then open **App info → View logs** and filter for `WNPerf`. Confirm the
lines identify `op`, `phase`, `layer`, bounded timings/counts, and a closed
`result`, and contain no account/group/message identifiers, npubs/pubkeys, URLs,
filenames/paths, notification or message text, ciphertext, keys/tokens, or raw
errors. Turn the switch off and repeat an action to confirm no new lines appear.
White Noise controls future emission only; retention of lines already written to
Logcat is controlled by Android/GrapheneOS.

### Send and attachment diagnosis

`op=text_send` and `op=media_send` follow one process-local operation from the
optimistic bubble through native admission and the authoritative timeline and
chat-list projections. Slow operations emit `pending_checkpoint_10s` and
`pending_checkpoint_60s` with their current closed send stage. Media sends add
`media_upload_start`/`media_upload_return`, `media_upload_reused`, and
`media_publish_start`/`media_publish_return`, so an incomplete pair identifies
whether upload or publication stopped returning. `media_publish_combined`
identifies native calls that uploaded and admitted the send atomically.
Accepted-pending responses are
reported as durable ownership rather than success, with
`engine_phase_unavailable` making clear that Android cannot see the deeper MDK
queue, MLS, storage, transport, or acknowledgement phases.

`op=message_forward` follows one forwarding operation from acceptance to its
terminal state. For each source attachment it emits `forward_source_lookup`
(`result=success layer=storage` when the plaintext was already in the Android
memory or disk cache, `result=success layer=mdk` when MarmotKit's retained copy
served it, `pending` on a miss), the `forward_source_download_start`/`_return` pair
around the native source download, and `forward_source_ready` for the whole
materialization; `forward_source_reference_resolved` appears only when an
optimistic reference had to be resolved through native history. Each destination
then emits `media_upload_start`/`_return`, `commit_lock_acquired` (how long the
batch waited for the shared group commit lock, omitted below 5 ms),
`media_publish_start`/`_return` per message and, on an uncertain-publish retry,
`convergence_start`/`_return`. `forward_complete` closes the operation with
`result=success`, `failure` or `dropped` (cancelled) and `count` set to the
completed destinations. A retry that reuses destination references shows
`media_publish_start` with no preceding `media_upload_start`. As with media
sends, a start phase without its return names the phase that stopped
answering, and no line carries a group, account, message, file, hash or URL.

`op=attachment_fetch` distinguishes explicit taps from automatic fetches and
records memory and disk probes, acquisition start, native snapshot and demand,
closed native transfer states, plaintext readiness, cancellation, and failure.
The schema deliberately excludes attachment IDs, hashes, filenames, URLs,
content, byte counts, raw errors, and stack traces. Native per-server attempts,
Blossom byte progress, queue depth, and cryptographic sub-step timings remain
outside Android's current FFI surface; the final native state and first missing
phase are the available localization evidence.

## MDK host timing integration

The 26 bridge stages use the published MarmotKit 0.9.20 `recordHostTiming` API.
Product export now uses environment-specific Aptabase configuration and MDK's
expanded, explicitly accepted Android consent scope. See
[Android usage and diagnostics](product-analytics.md) for the variable names,
first-launch/upgrade behavior, release gates, and pending operator validation.

| Events (`app_` prefix) | Measured work |
| --- | --- |
| `text_send`, `text_reply`, `message_edit`, `message_react` | One native message command attempt |
| `media_upload`, `media_send`, `media_download`, `media_list` | Native transfer, publication or media listing |
| `timeline_read`, `message_search_page` | One timeline page/read, with search pages separated |
| `chat_list_read`, `chat_row_read`, `member_ids_read` | SQLite-backed chat/member projections |
| `profile_read`, `display_name_read` | Native profile/name reads |
| `account_list`, `unread_summary`, `catch_up` | Account listing, unread projection, catch-up |
| `group_create`, `invite_accept`, `group_roster`, `members_invite`, `members_remove`, `admin_promote`, `admin_demote`, `admin_self_demote` | Existing traced group operations |

Each duration uses Android's monotonic elapsed clock, starts after IO dispatch
and ends when the block returns or throws. It includes native suspension/queue
wait, but excludes Android dispatcher admission, surrounding locks, UI decoding,
layout and rendered frames. Cancellation is a failed host attempt, **not** proof
that native publication failed. Retries and paginated reads are separate samples.
Unknown trace names never enter the product registry or timing recorder.

Compare stage duration buckets and outcomes by app version/environment to locate
slow call paths. Custom host events reach Aptabase only; they do not add OTLP
series, raw-duration traces or exact percentiles. The upstream native queue,
projection, acceptance and publication metrics provide the deeper breakdown.
Do not add their percentiles to these overlapping host measurements. Existing
Perfetto slices and the local WNPerf toggle remain independent of export consent.

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
On a physical device, every connected-test command must pass
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`; otherwise AGP
may uninstall the target during teardown and erase the local identity and message
history. After profile collection, restore the normal dev APK in place without
uninstalling or clearing data.

## Populated process-death startup regression

`PopulatedProcessDeathStartupBenchmark#populatedLocalRowsRemainInteractiveBeforeNetworkRelease`
uses the separate benchmark process and the ordinary target Application. Prepare an
owned authenticated fixture with persisted chats and a known `groupName` through
normal app/MDK operations before the run. The test does not reset accounts or
create an Android protocol-data cache.

The host must capture and restore the original connectivity state, make every
internet-capable transport unavailable, and pass `requireOfflineStartup=true`.
When using the state-preserving runner, set `REQUIRE_OFFLINE_STARTUP=true` and
select this class with `BENCHMARK_CLASS_FILTER`; supply the reviewed
`STARTUP_LOCAL_FRAME_BUDGET_MS` as for other startup qualification. This test does
not toggle radios itself and refuses online or switched-user fixtures.

Each of three iterations verifies the populated local list, presses Home, calls
`am kill` for only the fixture user, requires the old process to disappear, and
reopens the retained task. It rejects a retained PID, loading/recovery in place of
the populated local checkpoint, missing rows, or a row action that cannot open
the local transcript before network release. A foreground service that prevents
`am kill` is a failed qualification, not a skipped test. Macrobenchmark's
`killProcess()` uses force-stop and cannot substitute for this ordinary
process-death scenario. Retain the startup traces and instrumentation result
alongside exact APK/source/MDK provenance. Emulator runs qualify behavior only;
physical release-like timings and a reviewed device budget remain separate gates.

## Run Macrobenchmarks

### Isolated media component probe

The expanded upload/download size matrix and machine-readable aggregate
reporting are documented in [media-latency-matrix.md](media-latency-matrix.md).
The first emulator results are in
[media-latency-preliminary-baseline.md](media-latency-preliminary-baseline.md).
The controlled matrix with verified link shaping, its baseline and the latency and memory budgets it enforces are in
[attachment-latency-matrix-2026-10-03.md](performance-data/attachment-latency-matrix-2026-10-03.md) and
[attachment-native-ready-2026-10-03.md](performance-data/attachment-native-ready-2026-10-03.md).

For a diagnostic breakdown of native image downloads, encrypted Android cache
reads/writes, and platform decoding, use `MediaAttachmentLatencyProbe`. This is
an opt-in **debug component probe**, not a release qualification or UI
first-frame benchmark. It uses the separate `dev.ipf.whitenoise.android.medialatency`
package, never an existing dev, staging, or production store.

The probe creates a temporary identity and private group, uploads sixteen distinct
generated approximately 64 KiB PNGs to the configured default media service, and
verifies every download against the corresponding generated bytes. It reads no personal media or accounts.
Only fixed phase names, sample counts, and aggregate durations are exported to
instrumentation output. The test removes its temporary native store, cache, and
cache key after closing the runtime; the remote encrypted fixture remains
subject to the service's retention policy. Neither package is uninstalled.

The accompanying admission regression measures twenty memory-cache admissions
and twenty platform-encrypted disk admissions without permitting a remote fetch.
Each disk sample uses a fresh key to exclude memory-cache reuse; cleanup removes
only entries created by the test. Admission timing is not composed-frame timing.

The encrypted-cache read/write and image-decode measurements are an independent
test, so a failed network scenario cannot prevent their execution. To run just
the offline local phases, append `#measureLocalImagePhasesWithoutNetwork` to the
class selector below.

```bash
./gradlew --init-script scripts/media-latency.init.gradle \
  :app:assembleDevZapstoreDebug :app:assembleDevZapstoreDebugAndroidTest \
  -Pandroid.injected.build.abi=arm64-v8a
adb -s "$ANDROID_SERIAL" install -r -t \
  app/build/intermediates/apk/devZapstore/debug/app-dev-zapstore-arm64-v8a-debug.apk
adb -s "$ANDROID_SERIAL" install -r -t \
  app/build/intermediates/apk/androidTest/devZapstore/debug/app-dev-zapstore-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.ipf.whitenoise.android.media.MediaAttachmentLatencyProbe \
  -e allowMediaProbe true \
  dev.ipf.whitenoise.android.medialatency.test/androidx.test.runner.AndroidJUnitRunner
```

Verify both APKs' `output-metadata.json` application IDs before installation.
The explicit init script changes only local diagnostic builds, and the test
rejects any other target package. Do not use the general Macrobenchmark runner
below for this probe: that runner intentionally replaces the existing dev app.

The network phase makes 20 sequential native calls for the first image with no
Android plaintext cache, then 20 batches of the sixteen distinct references
through the actual three-slot gate (340 native download calls total, roughly
22 MiB of image payload). The transport and server caches may already be warm;
only the Android plaintext cache is bypassed. Each batch asserts the host
concurrency bound and byte equality. Reports separate per-request latency
including queue wait (320 samples) from whole-batch latency (20 samples).
Local phases use 20 new encrypted-cache entries and the production decoder.
Report first-sample effects through the maximum as well as nearest-rank p50/p95.
The opt-in run has a ten-minute outer guard and may take several minutes.

After fixture upload, the live test snapshots the shipped native aggregate
performance API before and after the download interval, including when a
download fails. `media_probe_native` output contains only twelve fixed phase
names, interval attempt/success/failure counts, duration sums, and numeric
histogram buckets with an overflow count. It does not enable telemetry export
or include unrelated snapshot fields. Native bucket counts are per-bucket, not
cumulative; histogram bounds are not exact measured percentiles. Counter resets,
saturation, or changed bucket bounds fail validation rather than producing a
misleading interval report. A missing phase has zero observations, not a zero
latency, and failed live runs remain failed even if the independent local tests
pass. Do not discard failures or substitute the local timings for network timings.

This probe does not measure receipt-to-visible pixels in a real chat album,
stalled-locator failover, per-request native phase attribution, internal HTTP
concurrency, or a signed release candidate. Those require their own fixtures and representative-device runs;
passing this diagnostic must not be treated as proof that all field latency
tails are eliminated.

### Deterministic Android media regressions

Run `MediaDownloadHostRegressionTest` for the actual app-state download and
encrypted-cache path with a suspended synthetic native boundary, and
`MediaImageBubbleLatencyTest` for the production Compose image bubble. The
host tests assert distinct-request ownership, three-slot admission, explicit
priority promotion, useful progress alongside a delayed native call, no
host retry loop after native timeout/integrity failure, account isolation, and
cache reuse after the caller leaves and returns. The Compose tests cover decoded
thumbnail first-frame rendering with a frozen frame clock, cold encrypted-index
hydration, and recomposition/navigation while a download is in flight.
Cold cached image bubbles and album tiles now load even when automatic
downloads are disabled. A cache-only read never falls through to the native
download API: corrupt or evicted entries return to the explicit download
affordance. The tests authenticate a deliberately damaged encrypted entry and
assert zero native calls, and tracked screenshots cover standalone light/dark
rendering and a loaded three-tile album.
If policy grants network access during a failing cache-only read, materialization
restarts with that new permission instead of leaving the image on a spinner.
Separate bubble/tile regressions hold the real encrypted read across this policy
change and assert exactly one native request followed by visible image content.

These tests do not reimplement native locator fallback. The pinned native
artifact disables loopback blob endpoints, and its exported constructors do
not expose the Rust-only opt-in. A mocked 10-second delay would not verify that
implementation. The real 12-second fallback assertion therefore still needs
a controlled public HTTPS service with a deliberately stalled first candidate
and valid later candidate; do not weaken TLS or address validation to run it.
Native HTTP concurrency and transport/integrity/crypto phase attribution remain
separate from the Android ownership counters.

### App journeys

Run startup plus the repeatable group-open journey without allowing AGP to
remove the authenticated target package:

```bash
ANDROID_SERIAL=<device-serial> \
  STARTUP_LOCAL_FRAME_BUDGET_MS=<reviewed-device-budget-ms> \
  scripts/run-performance-benchmarks.sh "$GROUP_NAME"
```

The script builds the normal dev and release-like APKs, replaces the dev app in
place, invokes only the startup and group-open before/after methods, and pulls
JSON plus Perfetto output into `benchmark/build/outputs/manual/`. Its exit trap
restores the normal dev debug APK even when a benchmark fails. Both target and
benchmark APKs are installed or updated in place; the runner never uninstalls a
package, so authenticated app data remains intact on a personal physical device.

Before the controlled Macrobenchmark iterations, the runner also records the
first cold launch after that in-place replacement. The explicitly selected
release-like benchmark build emits privacy-safe `WNPerf` startup milestones for
the system-splash handoff and the first authoritative local chat-list frame. The
host runner requires a cold Activity
launch, requires both milestones, rejects a Compose handoff at or beyond two
seconds, and rejects the first local frame when its conservative upper bound exceeds
`STARTUP_LOCAL_FRAME_BUDGET_MS`. Choose and review that budget from repeated
release-like measurements on the named device **before** the acceptance run; do
not pick a larger value after seeing a failing measurement. There is no default
budget. The startup/default suite rejects a missing budget before changing the
device; a filtered non-startup journey without one does not produce a qualified
startup report. It writes the exact APK SHA-256, named device/API/build fingerprint,
Activity launch timing, splash handoff timing, time to first app-owned Compose
UI, and time to local Ready state to:

```text
benchmark/build/outputs/manual/<UTC timestamp>/package-replacement-startup.json
```

The accompanying install, Activity launch, startup-log, and device-state files
are retained beside the JSON so a published result is auditable. This journey
measures an actual `adb install -r` package replacement and never clears app
data; a normal dev debug APK is restored in place on every exit path.

For a focused rerun, set AndroidJUnitRunner's comma-separated class filter via
`BENCHMARK_CLASS_FILTER`; the script still uses the same state-preserving path.
The group title argument is optional when the selected method does not use the
existing-group fixture. Every run retains `instrumentation.log` plus before/after
device, battery, and thermal metadata beside its JSON and traces.

To capture the Android recovery resource baseline, use the guarded network
journey on a supported physical Pixel:

```bash
ANDROID_SERIAL=<device-serial> \
  ALLOW_NETWORK_TOGGLE=true \
  BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.NetworkRecoveryBenchmark#validatedNetworkRecoveryPower" \
  scripts/run-performance-benchmarks.sh
```

This explicit flag authorizes five controlled airplane-mode cycles. The runner
captures the exact original airplane-mode state before replacing the APK and
restores it from the exit trap on success or failure; the instrumented test has
its own restoration fence as well. App data is preserved throughout because the
runner continues to use only in-place APK replacement.

Each iteration starts from the authenticated chat list, settles online work,
goes offline outside the measured trace, then measures a fixed 25-second
offline-to-online recovery window. The result includes UI frame timing, peak
target-process heap and anonymous RSS, system CPU/network/memory energy, the
number and summed duration of bounded recovery attempts, native catch-up time,
and any push-wake-lock duration. The accompanying Perfetto trace exposes target
process scheduling for CPU-time analysis.

Treat the power categories as system-wide hardware energy, not app-exclusive
attribution: the operating system and radio transition are part of the sample.
Compare repeated runs on the same device, build, fixture, brightness, battery,
network, and thermal state. Do not compare absolute values across devices, and
do not set a fleet-wide threshold until representative baselines exist.

### Idle background-delivery baselines and push-burst power

For the screen-off reconnect scenario use
`NetworkRecoveryBenchmark#backgroundValidatedNetworkRecoveryPower` with
`QUALIFICATION_USER_ID` and `ALLOW_NETWORK_TOGGLE=true`. It selects Push, checks
validated internet, backgrounds the app and turns the screen off before taking
Wi-Fi and cellular offline. The measured 25-second window restores connectivity
and requires genuine internet validation while the screen stays off. Both the
test and host cleanup restore the original Wi-Fi and airplane-mode switches.
Run one sample per invocation, then repeat balanced independent rounds.
The existing foreground `validatedNetworkRecoveryPower` measures UI recovery;
its energy result cannot qualify a background campaign.

`BackgroundIdleBenchmark` ([#2786](https://github.com/marmot-protocol/whitenoise-android/issues/2786))
measures the three remaining delivery postures the recovery benchmark above does
not cover, plus a representative push burst. Unlike the recovery journey, these
methods run no UI interaction inside the measured block; each is a fixed sleep
window while the app sits backgrounded, so only `idleMetrics()` applies (no
frame timing).

The product has no third "delivery entirely disabled" mode — it always resolves
to push or local/keep-connected (see `NativePushDelivery.resolvedNotificationDeliveryMode`).
`idleWithDeliveryDisabledPower` uses a controlled disposable fixture: Push
selected so the Local stream is off, the fixture Firebase receiver disabled so
FCM cannot enter, and notification permission revoked. It resumes the process
after permission/component changes, requires it to stay alive and screen-off,
and restores both exact platform overrides afterward. This experimental floor
is not a third product mode or a claim that background Android/GMS activity ceases.

```bash
ANDROID_SERIAL=<device-serial> QUALIFICATION_USER_ID=<disposable-user-id> \
  BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.BackgroundIdleBenchmark#idleWithDeliveryDisabledPower" \
  scripts/run-performance-benchmarks.sh
```

Swap the method name for `idleWithNativePushPower` or `idleWithKeepConnectedPower`
to capture the other two postures. All three default to a 60-second idle window
in one iteration per invocation. A sleeping screen may engage a secure keyguard,
so repeat independent invocations after unlocking. Short overrides are smoke
checks only and cannot establish energy budgets:

```bash
IDLE_WINDOW_MS=10000 \
  BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.BackgroundIdleBenchmark#idleWithKeepConnectedPower" \
  scripts/run-performance-benchmarks.sh
```

`pushBurstPower` observes the receive-side push-wake/catch-up cost of several
messages arriving together, which is a different thing from
`SecondaryAccountNotificationNavigationMacrobenchmark`'s UI-reaction-time
measurement against notifications already sitting in the tray — the burst must
arrive *during* the measured window here, not before it. It runs a single
iteration; watch `adb logcat -s BackgroundIdleBenchmark` for the "send the push
burst now" line, then send at least five messages with distinct fresh fixture
bodies from a disposable second account before the window closes. Set
`NOTIFICATION_TEXTS` to those bodies using the existing `;;` delimiter. The
benchmark requires temporary listener access on an explicitly named disposable
Android profile, counts each matching body once, rescans existing matching
cards for every generation, and fails if the full burst is absent at the deadline.
Actual Android posting time must follow the window start; delayed setup callbacks
and ambiguous wall-clock changes cannot qualify. The listener
ignores other profiles and packages before reading extras, emits no payloads, clears the
fixture bodies, and restores its prior access on exit:

```bash
QUALIFICATION_USER_ID=<disposable-user-id> ALLOW_RECEIPT_LISTENER=true \
  NOTIFICATION_TEXTS="<fresh-body-1>;;<fresh-body-2>;;<fresh-body-3>;;<fresh-body-4>;;<fresh-body-5>" \
  BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.BackgroundIdleBenchmark#pushBurstPower" \
  scripts/run-performance-benchmarks.sh
```

Same caveat as the recovery baseline: system-wide energy, same-device
comparison only, no fleet-wide threshold until representative baselines exist.

### Hosted qualification artifacts

Agent builds run on GitHub under [CI request policy](ci-request-policy.md).
Dispatch `android-staging-apk.yml` on the reviewed candidate with
`background_fixture=true` for configured Dev debug, benchmark target and test
APKs. This explicit fixture path uses the staging push gateway; ordinary PR
previews disable push and cannot prove FCM delivery. The artifact records source
SHA, workflow run/attempt, MDK revision, package identities and SHA-256 hashes.
Keep downloaded artifacts and raw device traces in private qualification storage.

Android shares a package's APK across user profiles. Before updating a configured
Dev package on a personal phone, preserve its exact installed APK and a fresh
private data backup. Use only an explicitly authorized disposable profile for
accounts, fixture messages and settings. Never uninstall, clear data, or restore
an older database. Follow the workspace's device migration recovery runbook.

`scripts/background_fixture_artifacts.py verify <artifact-directory> <source-sha>`
checks downloaded identity. Its `prepare` command accepts the preserved original
Dev APK, an **existing** development keystore and SDK `apksigner`. It can change
signatures only: all compiled ZIP entries must remain identical to hosted bytes.
It never generates keys or installs apps. The runner additionally verifies actual
APK package identities, matching signers, unchanged installed original bytes,
and identical arm64 MDK runtime bytes before any device mutation.

Run the benchmark script with `BENCHMARK_APK_DIR`, `ORIGINAL_DEV_APK`,
`QUALIFICATION_USER_ID`, SDK `APKSIGNER` and `AAPT`, and the selected device serial.
It skips Gradle completely in this route. Switch to the authorized profile first.
Process selection, installs, instrumentation and output storage are profile-scoped;
the prebuilt route temporarily disables Dev in other installed users to prevent
their accounts from starting with fixture configuration. The exit trap restores
the preserved original Dev APK in place before restoring their exact overrides.
If restoration fails or competing code appears, those users remain protected;
the captured override file identifies the recovery state. A concurrent Dev
installation invalidates the campaign. Ownership checks run before instrumentation,
after measurement and before restoration; the runner refuses to overwrite unexpected
code. Resolve the competing install and take a new private backup before retrying.
The host also captures and verifies fixture notification permission, FCM receiver
override and receipt-listener access, restoring them even if instrumentation dies.

### Direct platform collector for disposable-profile diagnostics

When secondary-profile Macrobenchmark file access fails, an explicitly guarded
on-device worker can source `scripts/background_perfetto.sh` and call
`background_perfetto_start <config> <fresh-trace-path> <private-launch-log>`.
Push the helper/config to the worker's owned directory first. The function returns
one positive collector PID after data-source startup. Source it with Android's
`/system/bin/sh` (mksh), which supports its `local` and `pipefail` extensions;
strict POSIX shells are not supported. Use an exclusively owned directory: the
trace path is opened later by Perfetto, so path checks cannot prevent competing
writers. The worker remains responsible for deadlines, process ownership,
finalization, state restoration and
trace validation. It neither changes app/profile/network settings nor qualifies
a battery campaign by itself.

Perfetto runs in a separate SELinux domain. A configuration redirected from a
shell-data file can become unreadable even after the shell opened it. The helper
pipes config through `cat` and both diagnostic descriptors through `tee`, so
shell-domain processes perform file access. Pipeline failure, missing/ambiguous
PID and a pre-existing trace path reject the start. Diagnostics are reserved with
`noclobber` before launch and `tee` appends instead of truncating; a log created
between the initial checks and reservation rejects the start. Rejected diagnostics
remain private instead of disappearing into inaccessible file descriptors. See
[Perfetto's Android tracing guide](https://perfetto.dev/docs/learning-more/android).

On the stock Pixel, the original file-stdin launch rejected the config as empty
with a SELinux read denial. Identical piped config and the checked-in helper each
produce parseable five-second smoke traces; invalid config is rejected with
nonempty retained diagnostics. These charging system-only probes establish the
collector repair, not reconnect behavior, fixed-window energy or delivery.

The [8–9 October Pixel report](performance-reports/2692-pixel-report-2026-10-09.md)
retains anonymous measurements, rejected campaigns, delivery observations and
restoration evidence. Its incomplete acceptance assessment must not be treated
as tracker closure or battery savings.

Keep an active, unsaturated bounded phase session for each diagnostic window.
The local phase sink expires after 30 minutes and caps output at 256 events; a
missing callback phase without a verified active session is not proof that FCM
never reached the app. Renew sessions only in unmeasured fixture setup, retaining
the previous export and recording the setup change.

### Resource campaign acceptance

`scripts/background_delivery_report.py <campaign.json>` validates measured,
anonymous records for all five scenarios. Its field contract is defined by
`CAMPAIGN_FIELDS`, `RUN_FIELDS` and `RESOURCES` in the script. Supply exact source,
artifact and MDK provenance, device model/API/build, scenario-specific budgets,
and actual measured records. Unknown fields are rejected without echoing values.
Missing platform measurements must stay missing; never replace them with zero.
The current resource vector covers CPU/network/memory rail energy, wake-lock and
service duration, and recovery attempts. Scheduled work and UID network activity
remain independent trace/device evidence in the [device matrix](battery-device-matrix.md).

Collect at least five balanced rounds per scenario in a shuffled order, fixed
windows and network/charging posture, with screen off, a live fixture process,
verified mode/permission, and restored state. Idle requires 60 seconds, reconnect
25 seconds and push burst 30 seconds. The validator's initial repeatability rule
rejects a temperature spread above 2°C or an energy range above 25% of the median.
These are conservative campaign screening rules, not fleet budgets. Preserve
rejected samples and diagnose noise rather than dropping outliers.

Set and review budgets from controlled baselines **before** retry/wake/service
changes, then compare matched rounds and trace the dominant cost. A passing
report always leaves tracker completion false: reviewed budget provenance,
before/after attribution and the holistic device matrix remain required. Rail
energy includes system activity and cannot establish app-exclusive mAh savings.

To measure group creation separately, use the state-preserving runner with an
explicit mutation argument. This creates ten persistent MLS groups:

```bash
BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#createGroupConversationOpen" \
  CREATED_GROUP_PREFIX="Benchmark group <run-id>" \
  scripts/run-performance-benchmarks.sh
```

`CREATED_GROUP_PREFIX` is an explicit mutation guard: the creation benchmark is
skipped unless it is supplied, because each of its ten measured iterations
creates and syncs a persistent MLS group. Omit it when measuring only startup
and the repeatable group-open journey.

To measure row tap to the first visible conversation transcript, run the warm
re-open and cold-process variants against the same cached group fixture:

```bash
BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#openGroupConversationVisible,dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#openGroupConversationVisibleCold" \
  scripts/run-performance-benchmarks.sh "$GROUP_NAME"
```

The warm method opens and closes the conversation once during each unmeasured
setup block. The cold method restarts the app process, waits for the local chat
list, and then measures the first conversation open. Both end the measured
`firstTranscriptVisibleMs` section when the transcript's anchored, non-empty
Compose node first intersects the physical display. `routeSettledMs` covers the
same tap through the frame after the 240 ms route tween completes, and frame
timing spans that entire measured block. Warm setup waits for both the chat
list's settled-route marker and disposal of the outgoing conversation controller,
so the measured reopen cannot reuse the short exit-retention window. Report median,
P90, and frame-overrun/jank metrics from the retained benchmark JSON; do not
substitute debug-build timings.

Run the one-shot invite journey by filtering to its test method:

```bash
BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#acceptInviteConversationReady" \
  INVITE_NAME="$INVITE_NAME" \
  scripts/run-performance-benchmarks.sh
```

Run the two scroll journeys against the same cached fixture. Neither needs a
group-name argument: the chat-list journey resumes to the list, and the
conversation journey takes the group it opens from `$GROUP_NAME`.

```bash
BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.ChatListScrollBenchmark#chatListScrollBaselineProfile" \
  scripts/run-performance-benchmarks.sh
```

### Conversation paging journeys

`ConversationPagingBenchmark` measures history paging the way a reader meets it:
long flings that cross several 50-row page boundaries and the 200-row window
cap, in both directions, plus the two moments a bounded window is most visible.
It needs a fixture conversation holding **at least 300 messages**; a debuggable
build can prepare one from **Settings → Developer Tools → Seed conversation
fixture**, which sends numbered synthetic messages into a chat you pick — use a
private test group, since every member receives them. Record that group's exact
display name as `GROUP_NAME`.

```bash
BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.ConversationPagingBenchmark" \
  scripts/run-performance-benchmarks.sh "$GROUP_NAME"
```

The runner's package-replacement cold-start report is not part of this journey.
If the release-like launch does not emit both startup milestones within its
wait — a busy engine after a large seed can push the first local frame past
it — pass `REQUIRE_STARTUP_MILESTONES=false` to continue with the chat-list
preflight alone; the paging results do not depend on that report.
`PAGING_DEEP_FLINGS=<n>` deepens the three deep journeys beyond their default
twelve flicks (passed to the benchmark as `pagingDeepFlings`); about forty-five
flicks on a 1,000-row fixture carry the transcript past the app's 600-row
retention cap, which is where the main-thread apply cost has to be read.

| Method | Journey |
| --- | --- |
| `deepOlderFling` | Twelve flicks into history without pausing. |
| `returnFlingAfterDeepHistory` | The same distance back toward the newest row, through the newer-page path. |
| `jumpToNewestAfterSaturation` | Jump-to-newest once the deep fling has evicted the tail from the bounded window. |
| `momentumHandoff` | Six flicks 150 ms apart, each landing while the previous fling still coasts. |
| `olderFlingWhileEngineCatchesUp` | The deep fling started right after a cold process launch, while sync catch-up owns the engine. |

### Unread-mention jump investigation

`jumpToUnreadMentionFromHistory` taps the real @ control and reuses the paging/frame
trace report. It is opt-in: the normal paging fixture is not an unread-mention fixture.
Use a private synthetic group and a second account to prepare more than 50 real unread
messages, with the only unread mention in the final message. The receiving account must
start at a saved older reading position with a uniquely identifiable synthetic row visible.
Use exact synthetic start and target text (including the rendered mention) as selectors.
Record fixture ID, unread count, source/APK hash, device, viewport, thermal/battery state and
whether the target is already loaded or the window changes during navigation.

```bash
MENTION_FIXTURE_ID="paired-sample-a" \
MENTION_START_TEXT="synthetic older anchor" \
MENTION_TARGET_TEXT="synthetic final mention text" \
MENTION_UNREAD_COUNT=60 \
BENCHMARK_CLASS_FILTER="dev.ipf.whitenoise.android.benchmark.ConversationPagingBenchmark#jumpToUnreadMentionFromHistory" \
  scripts/run-performance-benchmarks.sh "$GROUP_NAME"
```

This journey uses `CompilationMode.None()` and exactly one iteration: neither compilation
warm-up, a tail jump nor repeated open/close may consume the target before measurement.
Opening or attempting a jump consumes the fixture even if the run fails. Re-provision an
equivalent fixture through normal message/read flows for every additional sample; do not
reset native caches or read state. The supplied unread count/final-only condition is a
fixture declaration, not an automatic audit of the history. A missing start row, @ control
or target fails the journey. The target text and disappearing sole-mention control verify
landing/read completion, not smoothness or physical top alignment.

Compare independent samples before/after on matched builds and conditions, including mixed
tall text/media, cold measurement, keyboard/composer-reduced viewport, window/header changes
and cancellation. `WhiteNoise.conversation.mention.*` slices separate total, availability,
bounded approach, initial positioning, animation, layout waits and each correction write;
existing page window/prepare/apply slices separate runtime and main-thread work. These contain
operation names only. The report adds phase sums and correction counts to frame P50/P90/P99,
worst-frame and >32 ms evidence. A missing phase is unmeasured, never proof of zero cost.
Successful placement or elapsed time alone does not establish smoothness.

On the shared Hermes fixture, the guarded installer permits audited staging or exact-head
signed stable-preview artifacts, not unsigned dev/benchmark app APKs. Do not run this host
script there to bypass remote-first builds or the install gate. Use the approved signed
preview and a guarded manual @ tap/Perfetto capture for that fixture; report missing app
slices when GrapheneOS cannot emit them. Automated benchmark execution requires an admitted,
authenticated dev fixture on a compatible host. No additional always-required CI job is added.
The stutter cause remains unmeasured until these traces are collected; this instrumentation
is not itself a performance fix.

Beyond the scroll metrics below, each method reports the paging slices from the
[Conversation history pages](#conversation-history-pages) section: `windowCommandCount`
(every window command the journey issued — older and newer pages, but also
return-to-latest and exact-message jumps, which emit the same slice; the older
page boundaries a reader crossed are `runwayKeptCount + edgeReachedCount`, and a
fling journey where both are zero did not test paging), `pageWindowMs` (the engine's share), `pagePrepareMs` and `pageApplyMs` (the
app's share, preparation and main-thread commit), and three counts. Two must stay
at zero for paging to be invisible: `edgeStopCount` (the list rested on its
oldest row with more history behind it) and `edgeReachedCount` (a page landed
after the reader had already reached the old edge). The third, `runwayKeptCount`
(a page landed with rows still to spare before the old edge), counts the pages
that landed in time and is expected to be positive on any fling that paged. The paging
metric set carries no `PowerMetric`: on a Pixel 9 Pro XL the power rails were
sampled anywhere between 0 and 52 times across identical 13-second journeys, and
Macrobenchmark drops an iteration with no samples from the results wholesale.
Read energy from the retained `.perfetto-trace` files when rails were sampled,
compare a paging journey against the same fling inside an already-loaded window
so paging is charged only for what it adds to drawing, and never across devices.

Because that merge rule also hides every iteration a missing label touches, read
the per-iteration numbers from the traces rather than the JSON:

```bash
PAGING_REPORT_PYTHON=~/.venvs/perfetto/bin/python \
  scripts/run-paging-trace-report.sh benchmark/build/outputs/manual/<UTC timestamp>/
```

`scripts/paging_trace_report.py` (needs the `perfetto` Python package) prints one
row per iteration — pages crossed, window/prepare/apply sums and maxima, the
three edge counts, Choreographer frame P50/P90/P99/max and the count over 32 ms,
main-thread running time — followed by per-journey medians.

Both scroll benchmarks report frame timing, a `journeyDurationMs` trace section,
and peak process memory for the measured window: `memoryHeapSizeKb`,
`memoryRssAnonKb`, and `memoryGpuKb`. Read the memory values as a budget rather
than a target — decoded avatars, group images, and attachment buffers all grow
while a long list scrolls, and a jump there with unchanged frame timing points
at cache sizing rather than at rendering. Compare against the same fixture on
the same device; the absolute values are not portable across devices.

Invite acceptance consumes its fixture, so collect a ten-sample handoff set by
running this command once for each of ten distinct prepared invitations and
aggregate their `journeyDurationMs` values. Never reuse a consumed invite.

`StartupBenchmark` reports `timeToInitialDisplayMs` and frame timing with no
compilation and with the packaged Baseline Profile. `GroupFlowsBenchmark`
reports `journeyDurationMs`, frame timing, and a Perfetto trace for group open →
members visible, group creation → conversation ready, and invite acceptance →
conversation ready.

`StartupBenchmark` also reports one trace section per bootstrap stage, so a
regression names the stage that moved instead of only the total:
`client-construction`, `privacy-runtime-configuration`, `marmot-start`,
`notification-platform-setup`, `notification-privacy-setup`, `account-refresh`,
`account-activation`, `draft-reconciliation`, and
`external-signer-registration`. Each is the summed time inside that stage for
the iteration, so a bootstrap that retried counts every attempt.

The underlying slices are named `WhiteNoise.startup.<stage>`; search the
Perfetto slice table for `WhiteNoise.startup.` to see them beside the
`WhiteNoise.marmot.*` bridge slices below. They are emitted only while a trace
is active, and a section name is one of the fixed stage constants — never an
account, group, or message identifier.

`package-replacement-startup.json` is a separate one-shot device journey. Its
`timeToFirstComposeUiMs` is the conservative later value of Android's Activity
launch time and the monotonic system-splash handoff; this prevents Application
startup before the app trace exists from disappearing from the result.
`timeToReadyMs` is the first locally authoritative chat-list frame measured by
the process-local `app_start` trace. Schema 2 also records
`timeToReadyUpperBoundMs`, the sum of that trace duration and Activity launch
time. This deliberately counts their overlapping work twice so pre-AppState
launch work cannot escape the local-frame budget. The measured trace duration
and conservative upper bound are separate fields; compare like for like when
calibrating `acceptance.localFrameBudgetMs`. A recovery screen never qualifies
as a successful local frame. The UI's 15-second recovery deadline is a
presentation limit, not a performance acceptance budget. Do not
substitute emulator output for the named physical-device evidence required by
the startup issue.

The same traces include async `WhiteNoise.marmot.*` slices for the awaited MDK
calls in create, invite, accept, member-roster refresh, and admin flows. In
Perfetto, search the slice table for `WhiteNoise.marmot.` to separate bridge
time from Compose and coroutine scheduling time. For an ad-hoc 30-second capture
outside Macrobenchmark, start this command and perform one flow before it ends:

```bash
adb shell perfetto -o /data/misc/perfetto-traces/whitenoise-groups.perfetto-trace \
  -t 30s -a dev.ipf.whitenoise.android.dev sched freq idle am wm gfx view binder_driver
adb pull /data/misc/perfetto-traces/whitenoise-groups.perfetto-trace .
```

Trace section names contain only operation names, never account, group, member,
message, or relay identifiers.

The state-preserving script copies results and `.perfetto-trace` files under:

```text
benchmark/build/outputs/manual/<UTC timestamp>/
```

`./gradlew :benchmark:connectedCheck` remains available for an emulator or CI
device. Supplying `groupName` is required for authenticated group tests; group
creation and invite acceptance also require their explicit arguments. Tests
whose fixture or mutation argument is missing are reported as skipped. On a
physical device, pass the leave-APKs-installed property shown above; prefer the
state-preserving script for a local fixture because it also restores the debug
APK in place.

## Generate and package the Baseline Profile

Generate startup, chat-list, group-open, and member-roster rules on the prepared
device. Only launch-to-chat-list rules enter the Startup Profile; the broader
group and roster journey remains in the Baseline Profile so it cannot crowd
startup code out of the primary DEX:

```bash
./gradlew :app:generateBaselineProfile \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.groupName="$GROUP_NAME"
```

The generated profile is merged into and committed from
`app/src/main/generated/baselineProfiles/`. It is consumed by every supported
release variant; generation does not run implicitly during ordinary release
assembly. The leave-APKs-installed property prevents AGP teardown from removing
the authenticated target. After collection, replace the profileable variant
with the normal dev build in place—never uninstall first:

```bash
./gradlew :app:installDevZapstoreDebug
```

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
| Cold startup → initial display | _ms_ | _ms_ | _%_ |
| Open group → members visible | _ms_ | _ms_ | _%_ |

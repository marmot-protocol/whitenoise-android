# Still-image worker baseline

This characterization was introduced before worker-per-input retirement, when
the production transport reused its worker after successful requests. Preserve
those measurements separately from later lifetime measurements. The baseline
drives the per-input transport (#3373), which production does not install yet.
The audio/video containment, sender-trust, video-metadata and document-privacy
work in #3374–#3377 remains pending.

## Capture through the existing focused campaign

Use the exact reviewed draft head with the existing `android-instrumented.yml`
`media_sandbox_only=true` campaign, once with `media_sandbox_api=30` and once with
`media_sandbox_api=36`. Preserve the checkout revision, APK identity, device/API,
raw instrumentation output and `android-instrumented-reports` artifact. Run these Android device campaigns through GitHub.
The existing workflow collects connected-test additional output without a new
workflow, permissions or qualification threshold.

`MediaWorkerBaselineDeviceTest.characterizeDirectStillTransportWithFixedSyntheticCorpus`
runs 100 requests against the real transport for that exact source. The fixed ordering cycles
through PNG thumbnail, JPEG viewer, PNG inspection, JPEG EXIF, PNG region and SVG
operations. Inputs are a synthetic 256×256 gradient encoded as PNG/JPEG and a
fixed blue SVG; no account, contact, downloaded image or user media is used.
The header records each encoded input's SHA-256. Compare only matching input
hashes on the same device/API and build configuration: platform encoders can produce different bytes across Android versions.

The corpus has a 90-second admission window, checked between operations, plus
the existing per-request deadline for an operation already running. An unfinished
corpus fails after emitting its partial summary; its summary counts only attempted samples. Healthy operations must return valid results. Two additional negative
probes record malformed input and the existing debug watchdog stimulus. Both probes must return null. A null transport result has an untyped cause. Raw rows preserve `no_result` and
the stimulus; distinguishing refusal, startup failure and timeout requires
additional evidence.

## Raw records and phase definitions

Each row is emitted with instrumentation status key
`media_worker_baseline_json`. When AGP supplies `additionalTestOutputDir`, the
same rows are flushed to `media-worker-baseline.jsonl`, which the existing report
artifact collects. Require retained raw output for baseline qualification.
The writer refuses an existing output file to preserve earlier observations.

| Record | What it measures |
|---|---|
| Header | Corpus preparation time, device/API/build fingerprint and input hashes |
| Phase | Direct transport construction and close durations |
| Sample | One complete operation, including any bind, IPC and parent copy |
| Binding witness | Bind-to-connect time and kernel Binder death callbacks |
| Negative probe | Complete call time, stimulus, returned-pixel flag and binding state |
| Summary | Attempted/successful case counts and grouped p50/p95/max call times |

Operation timings exclude input hashing, report writing and caller-resource
sampling. Resource probes record their own duration. The first request is kept
separate from subsequent requests in summaries; there is only one first-request
sample, so it cannot establish a cold-start latency distribution. Percentiles
use nearest-rank observations per operation, outcome and request phase.
The transport does not type refusal, startup failure or timeout; failed sample
predicates remain in the `no_result` outcome group.

Every call reaches the direct transport. App pixel-cache behavior, chat-scroll,
notification and visible-viewer frame timings require separate measurements.
Only complete operation time is available for codec/IPC/copy work. The read-only binding wrapper adds
a death observer and timestamps; use the same observer for later comparisons.
This observer records the admission behavior of its exact source. Only a kernel
Binder death callback marks a witness as dead; connection callbacks and numeric
UIDs stay distinct.

Caller `/proc/self/fd` count and `VmRSS` are sampled when readable; unavailable
values are null. Their scope is the caller process. Isolated-worker memory,
global process counts and replacement gating remain unqualified; Binder endpoint
witnesses cover only this transport. Host/device process profiling remains
required for the worker-resource acceptance gate.

## What this baseline can establish

The historical baseline on source `643605a36b3d` characterized reusable bindings.
Its completed-reply fixture recorded that behavior while rejecting same-UID
replies as isolation attestations. The lifetime candidate replaces that fixture
with controlled session, retirement, exact-death and held-URI cases in
`MediaBindingRecoveryTest`. Those tests exercise owner ordering; hosted device
execution supplies the platform evidence.

Review raw healthy and negative samples before choosing a regression budget.
Historical baseline measurements remain separate from the candidate's per-input
lifetime behavior. Matched performance-budget approval, worker-resource evidence
and platform qualification for the lifetime candidate remain pending. Default
adoption stays held until those gates are reviewed.

The original `non-production` invariant exemption applies to the historical
test-only baseline slice. The lifetime implementation has separate behavioral
tests and platform requirements. All five issues remain open. Existing permanent
manual IDs and unchecked results are retained.

The [still-image worker lifetime](media-worker-lifetime.md) documents the
production owner and its qualification boundaries.

Production installation uses the per-input transport, switched in #3516 pending
approval of its proposed budget. That budget rests on the matched throughput
comparison and UI-level checks below, not on this sequential corpus.

## Healthy startup timing

The opt-in hosted API 30 startup trace runs
`MediaWorkerHealthyBaselineDeviceTest.characterizeHealthyDirectStillTransportWithFixedSyntheticCorpus`
against the existing release-derived `benchmarkRelease` app variant. This manual
fixture reuses the same 100 ordered synthetic operations and transport cleanup,
but omits the two debug fault probes. It is excluded from routine and focused
debug suites. The original containment selector and its fault probes are unchanged.

The header records the actual application debuggable flag, build configuration
and whether negative probes were selected. The trace init script requires a
nondebuggable, minified variant with resource shrinking. A successful capture
still does not establish production timing, lossless trace coverage or security
qualification: retain exact APK, compilation/runtime, corpus and device evidence
before comparing measurements. Debug characterization results must not be
relabeled as release performance.

The release-like trace mode does not retain its benchmark APK through the existing debug-APK failure upload. Preserve the trace reports; a failed run without a retained exact benchmark APK cannot qualify a performance comparison.

## Matched throughput comparison

`MediaWorkerThroughputDeviceTest` is an opt-in `ManualDeviceFixture`, excluded
from every hosted suite. It drives named transport variants through the same
synthetic corpus on one APK and device: `reuse` (the pre-#3373 reusable worker),
`per_input` (one cold generation per input) and `prewarm1`/`prewarm2` (per-input
generations with one or two clean spares). Repeat names for ABBA ordering:

```sh
adb -s <your-emulator> shell am instrument -w -r \
  -e class dev.ipf.whitenoise.android.media.sandbox.MediaWorkerThroughputDeviceTest \
  -e throughput_variants reuse,prewarm2,prewarm1,per_input,per_input,prewarm1,prewarm2,reuse \
  dev.ipf.whitenoise.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Scenarios: five first thumbnails after launch, each on a new lane once no
image worker or application zygote of the app is alive (`cold_launch`); eight
first thumbnails on new lanes started back to back, so an application zygote may
still be warm (`cold_first`); 24 idle thumbnails 400 ms apart; ten 1920×1440 viewer decodes 600 ms apart; the
six-operation mix back to back; 24-, 48- and 96-request bursts released
together; and eight overlapping scroll rounds of eight tiles every 400 ms.
Every input has distinct bytes. Rows go to instrumentation status key
`media_worker_throughput_json` and, when available, a
`media-worker-throughput-*.jsonl` file. Each request records latency, admission
wait and whether it reached the lane within the eight-second deadline.
Each scenario records worker starts, bind-to-connect times, the most live worker
processes and their RSS sum (read through `ps` as the shell UID), caller RSS
and FD maxima and the lowest system MemAvailable. RSS counts pages shared with
the zygote once per process; measure PSS separately with `dumpsys meminfo`.
Results are performance observations, never a security qualification. Compare
only matching corpus digests on the same device, API and build configuration.

## UI-level checks

`MediaWorkerUiDeviceTest` runs in the focused containment campaign against the
installed lane: a real three-column Compose grid of 120 distinct JPEG tiles,
each decoded through the production bubble decode and scrolled to its end by
touch; notification avatar artwork within the presenter's 2.5-second bound and
emoji artwork within its 250 ms bound, idle and behind a 24-image burst; and
viewer and photo-editor decodes cancelled once their input reached a worker,
followed by another image. `MediaWorkerUiThroughputDeviceTest` is the opt-in
`ManualDeviceFixture` that repeats the same scenarios for a named lane
(`-e ui_lane reuse|prewarm2|default`, one grid pass per run) and emits rows under
`media_worker_ui_json`. Each grid row also records the setup time and the
slowest loaded tile with its start, so an outlier can be placed in its run.
Both use synthetic images only.

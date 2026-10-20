# Still-image worker baseline

This first attachment-security slice adds tests and measurements only. The
production transport still reuses its worker after successful requests. Worker-per-input retirement and the isolation goals of #3373 remain pending.
The audio/video containment, sender-trust, video-metadata and document-privacy
work in #3374–#3377 remains pending.

## Capture through the existing focused campaign

Use the exact reviewed draft head with the existing `android-instrumented.yml`
`media_sandbox_only=true` campaign, once with `media_sandbox_api=30` and once with
`media_sandbox_api=36`. Preserve the checkout revision, APK identity, device/API,
raw instrumentation output and `android-instrumented-reports` artifact. Run these Android device campaigns through GitHub.
The existing workflow collects connected-test additional output without a new
workflow, permissions or qualification threshold.

`IsolatedMediaDecoderDeviceTest.characterizeDirectStillTransportWithFixedSyntheticCorpus`
runs 100 requests against the real unchanged transport. The fixed ordering cycles
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
use nearest-rank observations per operation, outcome and request phase. Refused results have their own outcome group.

Every call reaches the direct transport. App pixel-cache behavior, chat-scroll,
notification and visible-viewer frame timings require separate measurements.
Only complete operation time is available for codec/IPC/copy work. The read-only binding wrapper adds
a death observer and timestamps; use the same observer for later comparisons.
Production admission remains unchanged. Only a kernel Binder death callback
marks a witness as dead; connection callbacks and numeric UIDs stay distinct.

Caller `/proc/self/fd` count and `VmRSS` are sampled when readable; unavailable
values are null. Their scope is the caller process. Isolated-worker memory,
global process counts and replacement gating remain unqualified; Binder endpoint
witnesses cover only this transport. Host/device process profiling remains
required for the worker-resource acceptance gate.

## What this baseline can establish

`MediaBindingRecoveryTest.completedReplyBaselineReusesTheCurrentBindingAcrossDistinctInputs`
records the current reusable-binding behavior using completed same-UID fixture
replies. Those replies remain rejected as isolation attestations. Update this assertion with the reviewed disposable-worker implementation.
Current reuse remains the behavior tracked by #3373.

Review raw healthy and negative samples before choosing a regression budget.
Measured results and performance-budget approval remain pending. The
proposed worker-per-input gate must preserve retirement authority across account
reset/install and wait for authenticated process death before copying shared
output. A worker ignoring retirement must keep replacement admission closed. That implementation and API30/current-API evidence
are separate future work in this draft.

Invariant gate exemption for this baseline slice: `non-production`. This slice
changes test characterization and documentation. All five issues remain open.
Manual checklist and surface fragments stay unchanged until the behavior update;
existing permanent IDs and unchecked results are retained.

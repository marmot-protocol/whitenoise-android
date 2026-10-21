# Still-image worker baseline

This characterization was introduced before worker-per-input retirement, when
the production transport reused its worker after successful requests. Preserve
those measurements separately from successor lifetime candidates. The full
isolation goals of #3373 remain pending qualification.
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

The [still-image lifetime candidate](media-worker-lifetime.md) documents the
successor owner and its separate qualification requirements.

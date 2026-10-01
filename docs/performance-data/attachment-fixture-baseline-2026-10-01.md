# Attachment fixture baseline — 2026-10-01

Refs #2779/#2785/#2909. This test foundation qualifies the measured small received-file fixture only; it does not close those issues. Original samples, failures, checks and provenance are recoverable from the single [evidence manifest](attachment-evidence-manifest-2026-10-01.md).

## Method and provenance

Base: `ee00de17960db0a5e8b8ff97c256480f010e0516`; measured source: `4510a677cf1932657fc318866a516dc1e5b46271`. Later `5792f1408` only wraps probe lines. MarmotKit 0.11.0 / MDK `946e0547485c9a2c393c2048ec3a968fd50fb441`; library archive SHA-256 `5ab8bf5f7723ed2c07d15a07c5691d05eb8a37354882020c0b69b952d6418923`. The archived `provenance.json` retains probe/server/runner digests.

API30 arm64 emulator-5554, isolated package `dev.ipf.whitenoise.android.medialatency`, generated identities and loopback relay/Blossom through owned adb reverse mappings. Native peers publish/receive a genuine 1 KiB TXT; Android resolves the receiver's canonical history identity through its production resolver. After cold completion acquisition returns 503, still counting every GET/HEAD. Ten exact reads assert empty Android memory/disk caches; a final read closes/reopens the native database at the same path. Each session must have one upload, one completed GET/HEAD, 1040 uploaded/downloaded ciphertext bytes and zero acquisitions for all eleven local reads. Other host builds were active. No physical app/data changed.

| Distribution | Measured app APK SHA-256 | Instrumentation APK SHA-256 |
| --- | --- | --- |
| Play | `788a7263272d8904940696c4945185b0125f468dc5d2446d9457f596d81d51b6` | `626fc61f0d9ea5ecf1489e8b53a1510e0caf234f64274727e9eda77d1a90af42` |
| Zapstore | `a4721fbef8b1f72873ed51418dad9fea95aed9badc40abf5cf1d63cd53b9a78b` | `680ea29531aca24a26b369ed7664099d4fb51a212c16bfe7a2e613b933a36566` |

## Controlled reference budgets fixed before candidate measurement

The reusable [budget checker](../../tools/attachment-fixture/budget_checker.py) enforces **every** sample, exact 1 cold / 10 retained / 1 reopen cardinality, finite nonnegative metrics and successful exact reads. The runner combines this with ledger-derived HTTP/byte assertions and actual API/ABI matching; any breach, incomplete evidence or cleanup failure produces `qualified:false`, retains the report and exits nonzero. Standalone checker success means performance ceilings passed, not full qualification.

| Ceiling | Reference API30 arm64 | CI API34 x86_64 |
| --- | ---: | ---: |
| Cold latency |1500ms|1500ms|
| Retained/native-reopen latency |150ms|150ms|
| Sampled Java heap |32MiB|32MiB|
| Sampled native heap |128MiB|128MiB|

Profiles `reference-api30-arm64` and `ci-api34-x86_64` use the original pre-candidate ceilings. Reviewed-head CI reference samples also fit them (cold max344.07ms, local max28.98ms, Java max14745488bytes, native max92001200bytes); one CI session per flavor does not justify tighter thresholds. These are environment-specific fixture regression guards, not production release targets. The saturated-gate behavior requirement belongs to the separate comparison slice.

## Android baseline results

| Distribution / phase | n | Median ms | p95 ms | Max ms | Java peak bytes | Native peak bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Play / Cold | 3 | 435.64 | — | 448.31 | 15434584 | 61702224 |
| Play / Retained | 30 | 16.79 | 36.13 | 66.70 | 16393976 | 66391776 |
| Play / Native reopen | 3 | 34.66 | — | 37.61 | 8431440 | 104620480 |
| Zapstore / Cold | 3 | 298.06 | — | 298.13 | 9790568 | 61638640 |
| Zapstore / Retained | 30 | 4.86 | 12.53 | 13.36 | 11057520 | 66333680 |
| Zapstore / Native reopen | 3 | 10.26 | — | 26.79 | 13467320 | 104817568 |

All six reference sessions pass exact requests/bytes and the ceilings above. Genuine outgoing canonical availability is false in all six. Cold/reopen have only 3 observations per flavor, so no p95; retained has 30 correlated observations, nearest-rank p95. Memory is absolute heap sampled every 10 ms plus initial/final samples, not allocation deltas or guaranteed maxima. Latency includes sampler lifecycle. Socket-write bytes exclude HTTP/error headers, error JSON, TCP retransmissions and total link traffic.

## Host-only accounting baseline

Twenty Python-to-Python loopback samples per size, with 50 ms header delay, produce exactly 20 requests and 20×payload bytes. This validates fixture accounting, not Android/native throughput. Archived host JSON retains raw timings, traced-Python and process-RSS peak measurements.

| Payload | Host p50 / p95 ms |
| --- | ---: |
|1KiB|62.77 /85.57|
|64KiB|94.20 /106.65|
|1MiB|192.13 /544.05|
|8MiB|555.88 /1240.95|
|30MiB|2339.30 /4295.80|

## Failures, validation and remaining outcomes

Initial HTTP1.0 WebSocket negotiation and sender-receipt lookup failed; the fixture was repaired without substituting generated timeline rows or retained sources. An initial Gradle worker stalled without results; final focused tests used 2 GiB. Those attempts are not PASS and raw development transcripts are unavailable. Comparison setup-storage failures/excluded partials remain archived separately.

Both measured app/test variants built; all six actual emulator sessions passed. The original 13 host contracts and both-flavor instrumentation compilation passed; reviewed-head hosted controlled-fixture jobs passed both flavors. The update adds enforcing-budget and archive-integrity tests. Follow-up gate results are recorded in the PR; new-head CI and substantive review must pass before landing. Visual changes: none; no new Roborazzi PNG is required.

Native outgoing retention remains unavailable: [MDK #2135](https://github.com/marmot-protocol/mdk/issues/2135), owned by `mubarakcoded`. Join/promotion stays held on [MDK #2134](https://github.com/marmot-protocol/mdk/issues/2134), also owned by `mubarakcoded`, until adopted atomic demand promotion preserves both retry budgets and tapped-file priority, including native storage assertions. Android #2936 timeout adoption remains a separate prerequisite; Datawav #2973 cancellation cleanup is already in the base. Shared progress/lifecycle/APK ownership requires Datawav coordination; Danny's MDK #2106 is untouched.

App process restart, whole-device offline, real Android send-controller retention, large native incoming files, physical devices, protected workers, external Open/Save/installer handoff, production resume and native allocation redesign remain deferred. Native reopen and host Range support do not qualify them. No issue closure, merge or release claim.

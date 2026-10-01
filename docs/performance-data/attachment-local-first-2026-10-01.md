# Canonical local-before-network comparison — 2026-10-01

Refs #2779, #2785 and #2909. Coordinated behavior draft is stacked on
[fixture foundation #2978](https://github.com/marmot-protocol/whitenoise-android/pull/2978).
The production change is restricted to resolving canonical MDK bytes before Android
acquisition admission. Join/retry/promotion, outgoing retention, progress and
platform-lifecycle implementation remain excluded pending their prerequisites.

## What changed and what the comparison proves

Previously an Android cache miss entered the shared acquisition gate before the
native retained-source lookup. Three unrelated held downloads could delay a fully
local read. The resolver now checks Android memory/disk and native canonical bytes
before admitting a true miss. The native closeable lease remains caller-owned;
post-load cancellation/failure closes it. Existing queued-tap promotion and all
true-miss controls are preserved, including their unresolved native-contract risk.

[The saturated-gate negative control](attachment-local-first-2026-10-01/saturated-gate-regression.json)
runs the new regression against unchanged baseline production source: it fails with
the declared five-second deadlock guard while all three unrelated permits are held.
The candidate passes with those permits still held, zero explicit demands and no
new producer call. The temporary test overlay was restored. This demonstrates
admission independence in the Android/native-boundary fixture, not HTTP throughput
or a five-second production timing claim.

The actual packaged-native emulator comparison uses the identical generated 1 KiB
received TXT and cache-free retained reads from the foundation. Each session has
one cold acquisition, ten canonical reads with both Android caches empty, and one
native database close/reopen read. The endpoint rejects acquisition with HTTP 503
after cold completion, without suppressing request counters. All twelve reads must
match exact bytes. This is not app process restart, navigation, whole-device offline,
protected worker lifetime or external file handoff qualification.

Baseline already avoids repeated HTTP once bytes are native-retained; the behavior
change removes the Android gate dependency. There is no newly saved-request/byte
claim. Cold timing does not demonstrate a phase-specific throughput improvement or
reproduce the production complaint. #2785 and #2779 remain open.

## Provenance and predeclared acceptance

Baseline production commit: `4510a677cf1932657fc318866a516dc1e5b46271`.
Candidate production/test commit: `8909bf418870cad7793a876545e4c288d35e15f0`.
The final evidence commit changes documents only. Both use unchanged MarmotKit
0.11.0 / MDK `946e0547485c9a2c393c2048ec3a968fd50fb441`; artifact pin and emulator
context are in [foundation provenance](attachment-fixture-2026-10-01/provenance.json).
Per-distribution [Play APK digests](attachment-local-first-2026-10-01/provenance-play.json)
and [Zapstore APK digests](attachment-local-first-2026-10-01/provenance-zapstore.json)
identify baseline/candidate app and instrumentation APKs. Test code is identical.
Only fixture line wrapping changed in foundation `5792f1408` after those APKs
were built. The final stacked completion gate passes the formatted probe; the
production resolver blob remains unchanged since the measured candidate.
Measurement package: `dev.ipf.whitenoise.android.medialatency`; API30 arm64: Play uses emulator-5554; Zapstore uses newly created
emulator-5556 (`wn_2779_fixture_api30`, Pixel 2, 2 GiB RAM, 14 GiB data). Both sources
within each flavor use the same emulator and APK packaging; Play is universal,
Zapstore is arm64-only. The arm64 native library and artifact pin are unchanged.
No personal-device package was changed. APK switching used in-place installation.

Network: generated loopback relay/Blossom via owned adb reverse mappings; no shaping,
production endpoint or metered/Wi-Fi equivalence claim. Each session has a fresh
private generated fixture, and its durable server ledger survives native reopen.
The primary comparison alternates candidate 1, baseline 4, candidate 2, baseline 5,
candidate 3, baseline 6 within each distribution. The earlier foundation baseline 1–3
calibrates the committed budgets; it is not pooled into this primary comparison.
[Two storage-failed setup attempts](attachment-local-first-2026-10-01/setup-failures.json)
stopped in-place replacement on emulator-5554. Their qualified partial HTTP reports
remain in `setup-partial/`, excluded from the primary paired comparison. Recovery
created a new emulator, without uninstalling/clearing any existing app.
Raw ledger `at_ns` values preserve request timing/order. Other host builds were active.

[Budgets committed before candidate measurements](attachment-fixture-baseline-2026-10-01.md#controlled-reference-budgets-fixed-before-candidate-measurement):
exact bytes, exactly one GET/HEAD and 1,040 ciphertext bytes; zero additional
acquisition/writes after cold completion; cold <= 1,500 ms, retained/reopen <= 150 ms,
Java sampled peak <= 32 MiB and native <= 128 MiB in every measured phase. These are
fixture-specific regression ceilings, not production latency/release targets.

## Results

| Flavor / source / phase | n | Median ms | p95 ms | Max ms | Java peak bytes | Native peak bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Play / baseline / Cold | 3 | 285.82 | — | 286.00 | 9546632 | 61662992 |
| Play / baseline / Retained | 30 | 4.82 | 9.30 | 11.09 | 10788816 | 66354704 |
| Play / baseline / Native reopen | 3 | 7.32 | — | 10.53 | 13129376 | 104813872 |
| Play / candidate / Cold | 3 | 286.61 | — | 289.98 | 9425384 | 61651168 |
| Play / candidate / Retained | 30 | 3.05 | 5.74 | 6.73 | 10326960 | 61736672 |
| Play / candidate / Native reopen | 3 | 5.05 | — | 5.46 | 12601488 | 91641712 |
| Zapstore / baseline / Cold | 3 | 282.25 | — | 287.03 | 8581904 | 61436240 |
| Zapstore / baseline / Retained | 30 | 4.55 | 7.67 | 8.25 | 9862584 | 66168416 |
| Zapstore / baseline / Native reopen | 3 | 6.54 | — | 7.72 | 12289568 | 104618704 |
| Zapstore / candidate / Cold | 3 | 280.31 | — | 297.51 | 8645032 | 61413696 |
| Zapstore / candidate / Retained | 30 | 3.22 | 5.02 | 5.06 | 9579048 | 66109712 |
| Zapstore / candidate / Native reopen | 3 | 3.74 | — | 5.38 | 11972776 | 104331840 |

All twelve complete primary sessions pass the predeclared phase/memory ceilings.
[Per-session budget checks](attachment-local-first-2026-10-01/budget-checks.json) link the raw evidence.

Cold/reopen have three samples per variant/source: no p95. Retained has thirty
observations per variant/source, with nearest-rank p95 and within-session
correlation. Memory is the maximum 10 ms sampled absolute Java/native heap plus
initial/final samples, not allocation deltas or guaranteed maxima. Elapsed time
includes the sampler lifecycle. Ciphertext socket-write counts exclude HTTP
headers/error JSON, TCP retransmissions and total link traffic.

All twelve paired sessions preserve one upload, one GET, zero HEAD, exactly 1,040
uploaded/downloaded ciphertext bytes and a complete GET. All eleven subsequent
canonical reads add zero requests or ciphertext writes. Each genuine native sender
retention query remains unavailable; received success does not qualify outgoing.

## Passing validation and qualification gaps

- 69 focused tests pass in each Play and Zapstore flavor: resolver, native chunks,
  lease cleanup, cache races, saturated host gate and existing Open/APK/cancellation
  consumers. Tests use an isolated 2 GiB worker. The initial stalled worker produced
  no results and is not counted as PASS.
- The baseline-only new saturated regression fails as expected; its candidate
  counterparts pass. No baseline source fix or unrelated shared-worktree edit.
- 13 fixture/relay/runner host contracts pass with real sockets and durable ledgers.
- Shared fast gate passes both-flavor compilation, ktlint, detekt, Zapstore lint and
  stacked fixture instrumentation compilation. Both isolated app/test variants
  build; all twelve paired actual-emulator sessions pass the fixed budgets.
- Manual guide validator and 33 tests pass; MED-009/MED-017 companion checks stay unchecked.
- Hosted CI, substantive review, CodeRabbit actionable-thread resolution and its
  required meaningful 80% docstring coverage confirmation remain pending. Foundation CodeRabbit reports its review limit reached; its SUCCESS
  context is not a fresh substantive review or coverage confirmation. Draft status
  does not establish landing readiness. Rendering is unchanged; Visual changes: none.

| Retained outcome | Disposition |
| --- | --- |
| Received canonical reads before Android admission | Implemented and qualified to the tests/fixture scope above |
| Full received lifecycle, actual Open/Save/installer/device matrix and large incoming MDK sender | Deferred qualification; no process/offline/handoff/physical acceptance claim. Keep draft until any required landing gate passes |
| Genuine outgoing canonical retention | Excluded; native probe unavailable, MDK #2135 OPEN; real Android send/restart/offline matrix required |
| Join/promotion preserving retry budgets and tapped-file priority | Excluded until adopted MDK #2134 proves both together, including native storage budget assertions |
| Timeout repair/cold throughput | No claim until #2936 or successor lands and phase-specific baseline/candidate qualification passes |
| Cancellation cleanup | Datawav #2973 already merged into base and preserved; no new lifecycle change |
| Truthful shared progress, lifecycle overlap, APK/forwarding ownership | No overlapping changes; Datawav coordination and applicable tests remain required |
| Agent-sender | Danny #2106 untouched; no agent-specific changes/completion claim |
| Production resume/native allocation redesign/unsupported large outgoing files | Deferred explicit outcomes; no host-only substitution |

Full-plan completion remains pending. Only this independent slice is implemented;
there is no dormant dependent code, issue closure, merge or release authorization.

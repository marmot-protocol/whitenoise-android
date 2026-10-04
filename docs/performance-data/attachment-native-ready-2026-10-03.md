# Native READY without the engine's feed cadence, 2026-10-03

Context: [#2785](https://github.com/marmot-protocol/whitenoise-android/issues/2785) and [#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045).
This compares one Android change against the [controlled matrix baseline](attachment-latency-matrix-2026-10-03.md) on
the same emulator, links, sizes and procedure, and publishes the latency and memory budgets the matrix now enforces.
It is an emulator and loopback measurement, not a physical-device or production-network result.

## The change

The engine coalesces its subscription frames to one per 250 ms and applies that to a terminal frame too, so a transfer
that finishes sooner is announced up to 250 ms late even though the authoritative snapshot already says `READY`. In the
baseline a 64 KiB cold download was ready in the engine after 34 ms and opened after 265 ms.

The native observer now keeps one feed read pending and races it against short, read-only authoritative snapshot reads
that back off from 10 ms to the engine's own 250 ms cadence. The pending read is never cancelled to start another, so no
engine wakeup is dropped. The snapshot read never demands, retries or cancels work, and cancellation still releases the
owned subscription. Nine unit tests cover the race, the failure and cancellation paths and the read-only property.

## Method and source cohort

Alternating runs, baseline then candidate, three each, on one owned API 30 arm64 emulator (`wn_2779_fixture_api30`,
Play, `.medialatency` installed in place) from clean signed commits, MDK pin `122bd90ffac60bb6311346e228d0f609a18521ee`.

| Side | Source | App APK SHA-256 | Test APK SHA-256 |
| --- | --- | --- | --- |
| Baseline | `3436010c5233e568296e07115aed21cedaebe520` | `564a645365e6f1690752c52bcc2496221485bcbc937906608e41af25c0572a09` | `94f6ced2746d215858c5cd7dec3913b9e08c9ea935f77ec168b0e9bfe627a210` |
| Candidate | `ee19c3548bb53dff61cf4cfffbd0602958fb365c` | `02b02e1907ef543336efeb052aebd8716c8f2b2290aeb072831b6d622a809349` | `13c897e3cced31af15d7bc3423128053dfc5544869ab76b4a0487aae948fbae2` |

The runs were taken before the permission-ordering fix of #3023 was part of the build, so the exact heads are the
commits above, preserved privately. The candidate source is this change on the same tip, and later commits on both
branches change only documentation, comments and host tooling. Baseline runs 1, 3 and 4 and candidate runs 1 to 3 pass every stage. Baseline run 2 failed
and is preserved, as are the earlier failed attempts described in the baseline report.

## Result

`matrix_compare` accepts the candidate: zero differences in request count, byte count or retries in every cell, no
reader-visible metric slower, and eight reader-visible cell metrics faster. A change counts only beyond the observed
spread and ten percent. Open-ready lease is the whole cold download as the reader experiences it; post-READY wait is
what the shipping path adds after the engine's authoritative `READY`.

| Link | Size | Metric | Baseline p50 | Candidate p50 | Change | Verdict |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| unshaped | 64 KiB | lease_ms | 264.9 | 40.3 | -224.7 ms (-85%) | faster |
| unshaped | 64 KiB | post_ready_ms | 231.3 | 6.1 | -225.1 ms (-97%) | faster |
| unshaped | 1 MiB | lease_ms | 264.6 | 118.9 | -145.7 ms (-55%) | faster |
| unshaped | 1 MiB | post_ready_ms | 146.9 | 17.0 | -129.9 ms (-88%) | faster |
| unshaped | 8 MiB | lease_ms | 571.0 | 436.2 | -134.8 ms (-24%) | unchanged |
| unshaped | 8 MiB | post_ready_ms | 176.6 | 54.0 | -122.6 ms (-69%) | unchanged |
| unshaped | 30 MiB | lease_ms | 1721.4 | 1691.9 | -29.5 ms (-2%) | unchanged |
| unshaped | 30 MiB | post_ready_ms | 558.0 | 547.8 | -10.2 ms (-2%) | unchanged |
| wifi | 64 KiB | lease_ms | 266.1 | 107.1 | -159.0 ms (-60%) | faster |
| wifi | 64 KiB | post_ready_ms | 191.6 | 26.6 | -164.9 ms (-86%) | faster |
| wifi | 1 MiB | lease_ms | 516.6 | 426.2 | -90.4 ms (-17%) | unchanged |
| wifi | 1 MiB | post_ready_ms | 112.8 | 19.3 | -93.5 ms (-83%) | unchanged |
| wifi | 8 MiB | lease_ms | 2587.4 | 2736.0 | +148.7 ms (+6%) | unchanged |
| wifi | 8 MiB | post_ready_ms | 89.1 | 52.0 | -37.1 ms (-42%) | unchanged |
| wifi | 30 MiB | lease_ms | 9217.5 | 10202.5 | +985.0 ms (+11%) | unchanged |
| wifi | 30 MiB | post_ready_ms | 553.6 | 523.0 | -30.6 ms (-6%) | unchanged |
| constrained | 64 KiB | lease_ms | 518.2 | 342.8 | -175.3 ms (-34%) | faster |
| constrained | 64 KiB | post_ready_ms | 215.5 | 30.4 | -185.1 ms (-86%) | faster |
| constrained | 1 MiB | lease_ms | 2536.8 | 2605.9 | +69.1 ms (+3%) | unchanged |
| constrained | 1 MiB | post_ready_ms | 19.8 | 5.8 | -14.0 ms (-71%) | unchanged |
| constrained | 8 MiB | lease_ms | 19207.2 | 19393.6 | +186.4 ms (+1%) | unchanged |
| constrained | 8 MiB | post_ready_ms | 66.1 | 53.1 | -13.0 ms (-20%) | unchanged |

Material improvements are the 64 KiB file on every link (open-ready 265 to 40 ms unshaped, 266 to 107 ms on Wi-Fi, 518
to 343 ms constrained) and the 1 MiB file unshaped (265 to 119 ms). Cells where the transfer itself is longer than the
engine's update interval are unchanged within noise, as expected: there the feed was already current. Warm reads moved
by under one millisecond in either direction (for example 2.8 to 3.5 ms at 64 KiB), within spread and on a path this
change does not run.

The probe's own recorder of the raw engine feed still shows the 222 ms delivery delay in the candidate runs. That is the
engine, unchanged. What changed is that the shipping path no longer waits for it.

## Budgets

[`matrix_budgets.json`](../../tools/attachment-fixture/matrix_budgets.json) holds the budgets and
`matrix_budgets.py` applies them to a pooled set of runs, failing on a ceiling breach or a missing measurement. A ceiling
is `fixed + per MiB x size` of the budgeted median and rejects a regression. A target is where the owner should land and
may be unmet today. Ceilings come from these measured medians with headroom; they are regression gates, not a claim
about field latency.

| Phase | Metric | Ceiling | Target | Owner |
| --- | --- | --- | --- | --- |
| Preparation | synchronous preparation visible to the reader | 8 ms | 2 ms | Android |
| First visible progress | demand to first progress | 500 ms | 350 ms | Android and engine |
| Transfer completion | client cost beyond the server's own time | 120 ms | 80 ms | Android and engine |
| Verification | body complete to authoritative `READY` | 6 ms + 25 ms/MiB | none yet | engine |
| Verification and materialization | `READY` to open-ready | 50 ms + 20 ms/MiB | 10 ms + 5 ms/MiB | Android |
| Materialization | warm and restart leases | 10 to 15 ms + 22 ms/MiB | 5 ms + 5 ms/MiB | Android |
| Engine upload (unshaped) | upload minus server time | 30 ms + 30 ms/MiB | none yet | engine |
| Memory | sampled Java and native peaks | Java 40 + 2.5 MiB/MiB, native 80 + 6 MiB/MiB | hold | Android, engine |
| External dispatch | call into the open path | 50 ms p95 | none | Android |

External dispatch is not measured by this matrix. It is bounded by the [received-APK report](attachment-apk-installer-2026-10-03.md),
where dispatch took 1 to 8 ms on API 30 and 36, so the ceiling has an order of magnitude of headroom.

Applied to the pooled runs, the **baseline breaches the post-READY ceiling in five cells** (the 64 KiB file on every link and the
1 MiB file on the unshaped and Wi-Fi links) and nothing else. The **candidate breaches no ceiling.** The remaining
targets the candidate does not yet meet are all in two places: materialization of 8 MiB and larger files (a whole-file
copy, 54 ms at 8 MiB and 548 ms at 30 MiB against targets of 50 and 160 ms) and the post-READY wait of small files on
shaped links (27 to 30 ms against 10 ms), where the backoff reads the snapshot at a growing interval.

## What this leaves, by owner

- **Engine.** Verification and decryption take about 18 ms/MiB after the last body byte (535 ms at 30 MiB, 152 ms at
  8 MiB), the engine's feed still coalesces at 250 ms, and an upload that spans a group commit is rejected after its last
  byte. The first two are budgeted with no target; the last is the epoch finding in the baseline report.
- **Android storage.** Whole-file copy materialization above, and the whole-array memory boundary: 66 MiB Java when
  sending and 89 MiB when receiving a 30 MiB file. Neither is changed here.
- **Not measured.** Physical Wi-Fi, a production network and external dispatch beyond the figures cited above.

## Reproduce

```bash
python3 tools/attachment-fixture/matrix_compare.py --baseline base-1.json base-3.json base-4.json --candidate cand-1.json cand-2.json cand-3.json
python3 tools/attachment-fixture/matrix_budgets.py cand-1.json cand-2.json cand-3.json
```

Raw reports, logs, checksums and the preserved failed attempts stay private and durable.

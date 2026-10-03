# Ordinary Android attachment job interruption

This qualifies an actual ordinary WorkManager job stop and compatible native
checkpoint recovery. It does not qualify process death, representative throughput,
physical-device behavior or complete attachment UI flows. Refs #2878 and #2779.

## Method and source

Both generated peers publish a genuine 4 MiB document using the Android send
controller. Automatic demand uses the generated receiver's real saved document
matrix and validated Android connectivity. The fixture observes a completed native
permission update before enqueueing; it never grants permission directly, force-runs
work or calls deliberate Retry. The app goes Home, its Activity becomes stopped,
and no interactive intent, foreground service or user-initiated job is present.

At a held 2 MiB ciphertext prefix, the fixture finds its exact WorkSpec in the own
application's JobScheduler and asks Android to time out that job. Coarse worker
reason/attempt diagnostics and RUNNING → ENQUEUED → RUNNING transitions prove the
same WorkSpec resumes. Separately, the fixture disconnects the held HTTP response.
The next GET sends compatible Range/If-Range, receives 206, and is held again at
3 MiB until the resumed worker is observed. Partial plaintext is unavailable at
both holds. Releasing the suffix completes the same work and exact native readback.
A scheduler stop alone is not reported as causing the HTTP disconnection.

Android source cohort: `cb3a6c6d3b2745dabbb647f98cb5d4118f04c392` plus the nine-file
frozen overlay whose manifest SHA-256 is
`4ebfc6fa45a966623b4f79bed00fd2f59baf528c4add44dc42576bf636a1e375`.
These APKs predate the qualification commit's Git label; do not relabel them as
committed-head builds. MDK remains `122bd90ffac60bb6311346e228d0f609a18521ee`, with
arm64 native SHA-256 `073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.
Only owned isolated API 30/36 emulators and `.medialatency` updates were used.

## Results

All four final sessions pass the independent ledger and platform assertion checker.
Every session records one upload, two GETs, zero HEADs, and exactly **4,194,320
ciphertext bytes in each direction**. Range starts at 2,097,152; the prefix is not
transferred twice. First-run stop diagnostics report zero-based attempt 0, with
reason unavailable on API 30 and Android timeout reason 3 on API 36. These Android
counts are not native retry budgets.

| API / flavor | Overall elapsed ms | Sampled Java peak MiB | Sampled native peak MiB |
| --- | ---: | ---: | ---: |
| 30 / Play | 33,127.15 | 25.54 | 113.58 |
| 30 / Zapstore | 32,454.62 | 20.52 | 102.10 |
| 36 / Play | 33,473.96 | 22.22 | 102.81 |
| 36 / Zapstore | 32,880.47 | 20.04 | 101.57 |

Elapsed time includes deliberately held responses and Android rescheduling. These
are functional observations, not a cold-download speed comparison. Existing strict
1 KiB budgets are unchanged and explicitly inapplicable to this 4 MiB scenario;
the checker never reports performance qualification.

| APK | SHA-256 |
| --- | --- |
| Play application | `1fa956ddab777787886f559f09a84396445c1c1a8c5d2877b866b814fd2a0559` |
| Play tests | `bb57fcab89eb82b04c1ddcba904306b8e7390f1b0747ac8b9ae74e3db49785f9` |
| Zapstore application | `3ce2126fcb4805995f17b3ed09695816ea27c42d47917494240b7a0b763711fa` |
| Zapstore tests | `c993b9b170e1ab5f39d731b7a71a8626602534106a17fb63a34bbd51701763b3` |

## Failures, host checks and limits

Private durable `planning/wn-five-closure-20261003/automatic-job-attempt1` through
`attempt7` preserve source overlays, logs, reports, ledgers and matching APKs.
Attempts 1/4 failed build/static checks; attempt 2 lacked the test constructor's
real connectivity registration. Attempt 3 passed API 30 with the Activity foreground,
but API 36 failed a shell-output assertion after a real job stop. Attempt 5 failed
API 30's shell-output assertion and API 36's native admission; the latter recorded
`NativeAttachmentTerminalException` but not its exact enum state. None is relabeled
as final stopped-Activity qualification. Attempt 6 passes all four cases; final
attempt 7 additionally requires a new ENQUEUED observation after the first RUNNING
state, avoiding an initial-enqueue false positive. The timeout-output checks now recognize
the actual API-specific messages, and terminal native admission fails explicitly.

Separate held-commit unit tests reproduced an actual settings race in both directions:
native permission could read an obsolete matrix before the queued preference save.
The production fix revokes first, then waits for preference commit before evaluating.
Both new tests fail without that ordering and pass in each flavor alongside the five
existing generation-fencing tests. This proves the settings defect; the earlier API
36 exception alone does not prove its precise cause. Initial helper/static failures
and the failing-before-fix XML are retained. The 63 reusable host fixture tests and
33 guide tests pass. The final focused set passes 38 tests per flavor, and the
shared completion gate passes compilation, ktlint, detekt and Zapstore lint.
Host tests do not count as Android execution.

Process-kill checkpoint recovery, incompatible checkpoints across Android job stops,
pause/backlog/metered combinations, complete app UI and physical acceptance remain
unqualified. Representative transfer/memory qualification under #2785 is separate.
No issue closure or full-plan completion is claimed.

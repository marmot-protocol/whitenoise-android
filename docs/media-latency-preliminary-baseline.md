# Issue #2785: preliminary media latency baseline

This is one guarded emulator run on 2026-09-29, using the synthetic probe at
source commit `212528dd27ad8c6d59a9840f8292f2dcf7fe0485` and MarmotKit
`946e0547485c9a2c393c2048ec3a968fd50fb441`. The app and test APK SHA-256
digests are in the [size matrix report](performance-data/issue-2785-emulator-size-matrix.json).
The network path and shaping were **not independently verified**, so all three
reports label the network `unknown`. This is a starting measurement, not a
reproduction of the staging complaint or a release target.

The [size matrix report](performance-data/issue-2785-emulator-size-matrix.json)
contains all attempts, outcomes, payload counts, Java/native heap peaks, and
native phase histograms. The guarded test passed in 150.588 seconds, with all
28 uploads and 28 byte-verified downloads succeeding.

| Payload | Count | Preparation p50 / p95 | Upload p50 / p95 | Cold download p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 64 KiB | 20 | 0.66 / 2.29 ms | 2,713 / 2,985 ms | 1,169 / 1,879 ms |
| 1 MiB | 5 | 2.55 / 6.46 ms | 4,331 / 4,627 ms | 1,827 / 3,272 ms |
| 8 MiB | 3 | 10.94 / 12.95 ms | 6,885 / 8,508 ms | 4,555 / 4,594 ms |

The native download histogram intervals show that waiting for response headers
accounted for 23,823 of 26,675 ms across the small downloads, 8,922 of 10,054
ms across medium, and 8,514 of 12,748 ms across large. The HTTP response wait
is the largest observed phase in this fixture. These interval sums cannot
identify whether the wait came from the server, storage, or transport; they do
show that the measured bounded import copy is much shorter. Uploads have a
native total but no equivalent server phase breakdown yet.

The separate [cache admission report](performance-data/issue-2785-emulator-cache-admission.json)
passed 20 warm-memory and 20 encrypted-disk hits without invoking a native
fetch. Median admission was 0.069 ms from memory and 3.897 ms from disk. The
[local phase report](performance-data/issue-2785-emulator-local-phases.json)
records median encrypted-cache write/read and image-decode times of 4.91,
3.76, and 0.28 ms respectively. Those fixtures use a generated image and do
not represent the full UI materialization path.

These runs report known plaintext payload bytes, while measured network bytes
remain `null`. The heap values are absolute sampled peaks rather than
allocation deltas. The five-sample and three-sample p95 values are descriptive
and too sparse to set budgets. Next measurements need a verified unshaped and
constrained network fixture, a near-limit transfer, process recreation,
first-visible-progress and external-dispatch timings, retry/cancellation
coverage, and a physical test device. Set target budgets only after those
conditions produce repeatable baselines, then compare exact APKs before
accepting an optimization.

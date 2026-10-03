# Unknown-length native and Android control qualification

This functional slice supports #2045 and the five-issue [closure contract](../attachment-remaining-closure.md).
It does not close any issue or qualify representative performance or full manual flows.

## Method and results

Generated peers use the shipping Android send controller and pinned native runtime.
A 4 MiB plaintext upload becomes 4,194,320 ciphertext bytes. The loopback response
omits Content-Length and holds after 2 MiB. A genuine positive native observation
must have neither a total nor a percentage. The held body must expose no plaintext
lease. The shipping file control renders that observation at 200% font scale in RTL;
its localized spoken byte-only label and clickable bounds of at least 48 dp are checked
using real Android Compose semantics. The response is then released, exact plaintext
is verified, and three local reads succeed while acquisition is unavailable.

Both flavors pass on owned API30 and API36 arm64 emulators: one upload, one GET,
zero HEAD, exact ciphertext bytes in each direction, and zero repeat acquisition.
The same measured APKs are used on both API levels.

| Session | Overall ms | Java peak MiB | Native peak MiB |
| --- | ---: | ---: | ---: |
| api36-play-unknown-length | 2411.15 | 39.46 | 98.81 |
| api36-zapstore-unknown-length | 2449.77 | 37.27 | 101.84 |
| play-unknown-length | 2417.66 | 32.84 | 99.15 |
| zapstore-unknown-length | 2177.29 | 27.75 | 100.78 |

Overall timing includes the held prefix and UI assertions. Peaks are absolute heaps
sampled every 10 ms and may miss brief spikes. One session per cell does not establish
variance or a release SLO. The original 1 KiB budgets remain unchanged; they do not
apply to this 4 MiB functional cohort. The API36 environment profile adds exact API/ABI
validation, not a new or relaxed performance ceiling. No physical device was touched.

## Provenance and preserved failures

The APK cohort is base `09b64a4` plus the frozen source overlay, not a rebuild labelled
with a later committed head. That base's source tree matches the signed fixture lint
correction `42d3b79acd5fe32ab40576b2a2ae25f699a2345b`.
Frozen source-manifest SHA-256: `b8aaaf36679aa8b14b002746739f48bf33bf40ad2f7ae078ceae0af53db9ec82`.
MDK `122bd90ffac60bb6311346e228d0f609a18521ee`; arm64 native library SHA-256
`073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.

| Artifact | SHA-256 |
| --- | --- |
| Play-app | `5f8d5871176d9f7cb7c5166bacf9b50e38489633bdafbed2651f3b18667fa9e9` |
| Play-test | `21568869ea423e7c93626068379cce3d714595e5795a0bca66c2b4cf933accc3` |
| Zapstore-app | `ba718cc5eb9b818d9aa35d37a37b65e00f636e3c224c4e5062fb91e97718bced` |
| Zapstore-test | `270daadd686a4629a81556c6cacce46c6d510a9acb6c96955493044b81d274fa` |

Private durable evidence roots `planning/wn-five-closure-20261003/unknown-length-attempt1`
and `unknown-length-ui-attempt1` retain generated raw ledgers, all source overlays,
APKs, reports, build failures and checksums. The first native-only cases pass but
have no UI qualification. Pre-runtime failures included lint formatting/import
findings and a private APK selector expecting the wrong output directory; they
were corrected before native execution. These failures are not discarded or
relabeled as native passes. All four final native-plus-UI cases pass. No public
evidence archive was expanded.

Host-only validation: 51 fixture/checker tests pass. Worker-stop diagnostics have
separate Robolectric regressions on API30/36 in both flavors; those tests do not
prove actual Android scheduler interruption. Actual background/lock/job-stop,
full MED-024/MED-025, physical acceptance and representative performance remain
separate required outcomes.

## Follow-up source qualification

The final background/keyguard fixture source is separately qualified using the
same production APK bytes and the newer Android-test APKs listed in the
[screen-lock report](attachment-screen-lock-2026-10-03.md). The host now preserves
body pacing after releasing a hold, so these timings include paced suffix IO:

| Session | Overall ms | Java peak MiB | Native peak MiB |
| --- | ---: | ---: | ---: |
| api36-play-unknown-length | 3668.54 | 35.99 | 101.83 |
| api36-zapstore-unknown-length | 4349.38 | 30.62 | 101.85 |
| play-unknown-length | 3677.50 | 27.96 | 99.23 |
| zapstore-unknown-length | 4008.30 | 27.70 | 100.81 |

All four pass with exact plaintext, one GET/zero HEAD and zero repeat acquisition.
These are not directly comparable timing cohorts or a performance improvement.
Final host-source digests and all 12 latest unknown-length/background/lock sessions
are retained in `platform-lock-attempt2`. The [background](attachment-background-2026-10-03.md)
and [lock](attachment-screen-lock-2026-10-03.md) reports qualify those limited
Android lifetimes; actual job-stop/resume and full manual flows remain pending.

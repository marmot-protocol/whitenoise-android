# Pixel retained platform companion — 2026-10-01

Refs #2779/#2785/#2909; partial qualification for behavior draft #2980. See the [paired native report](attachment-pixel-2026-10-01.md) for the earlier twelve small-file sessions and the [single archive manifest](attachment-evidence-manifest-2026-10-01.md) for all reports, failed runs, exact diagnostic sources and provenance. Raw sessions remain outside Git.

## Method and source

Pixel 9 Pro XL, API 37, arm64. User authorized an isolated fixture. Generated native peers exchange exact 1 KiB received content; Android resolver memory/encrypted-disk caches are empty before native reads; materialization creates a generated viewer file. Three unrelated production Android host download permits are occupied by real controlled HTTP bodies, then the fixture denies new acquisition. The production resolver/materializer, external Open and Downloads Save must still deliver exact bytes while all three holders remain active. These holders are fixture HTTP jobs, not three genuine MDK download jobs. The device stays online.

Open uses the production FileProvider URI/flags and a forced generated external viewer component; its independent process records received bytes/SHA-256. The test shell reads only that generated receipt because instrumented-process package visibility prevented direct querying. Save uses the production Downloads helper; the test reads the unique app-owned MediaStore result. Missing file and absent handler do not report Opened; the materialized artifact remains readable after handoff failure. An injected resolver IOException propagates; this is not native corruption qualification. Cleanup removes only generated app-owned files/Downloads rows/grants and owned adb mappings.

Candidate production source `60349883accc61fd21372250a5736770093cd243`; pre-fix foundation-branch baseline `f523c512d0b18388a5e22601cbcbcc6098f17f4a` (not the later merged master checkout). Test Kotlin/manifest are an archived diagnostic overlay outside Git; production code is unchanged. MarmotKit 0.11.0 / MDK `946e0547485c9a2c393c2048ec3a968fd50fb441`; arm64 native SHA-256 `db12b60dabd3981922132ca15b1d109dabc64da648e1365a15a9c83ea9a93dba`. Recorded installed app/test/helper digests were checked before every session. Final overlay hashes and reproduction instructions are archived.

| Source / flavor | App APK SHA-256 | Diagnostic test APK SHA-256 |
| --- | --- | --- |
| candidate / Play | `51984ea5cfb51c08aadeacc74956075abf4d5595626c5dc09ac0f87eb4ec89d1` | `b6bb424c7c1d6d7010766d43140567798ee3a69a9c38a024e7ecef8b429b36c1` |
| candidate / Zapstore | `f51f0d8198b6f444443e540c769ad3efbb2970635d1421d775a0ef854ed5d717` | `554e2633fbd2f330579bbb7f3963bc8268a2a23d2ca8833c7586b56df7a7de1d` |
| baseline / Play | `83576384fcd05c91b6572a595c728e0da7649d511ba9e65606d8f65e80949b4b` | `31d50a5671de954e0ef25ee91cd190e63b676117ef2611fbdde4c6650831eb2c` |

Generated viewer APK SHA-256: `e6634c9802a5fc7a1feb35882393e4949e5ca3a848cc6f0dcf37bb3f8c73d79f`. It was absent before installation. Fresh private backups precede in-place isolated app/test updates. No uninstall/clear or personal White Noise package update was performed. The candidate is restored afterward; fixture APKs remain installed.

## Results and accounting

One final qualified session per flavor; no p95 or causal performance comparison. Play session 4 and Zapstore session1 pass exact viewer and saved bytes, all holder assertions, truthful missing-file/no-handler results, source-failure propagation, original native budgets and cleanup. The baseline was already able to avoid repeat transfers; this demonstrates admission independence, not newly saved bandwidth or faster cold throughput.

| Candidate flavor | Cold ms | Max of 10 retained reads ms | Native reopen ms | Companion ms | Companion Java peak bytes | Companion native peak bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Play | 571.05 | 69.92 | 55.32 | 3893.70 | 7381728 | 64561232 |
| Zapstore | 585.14 | 61.44 | 62.06 | 3676.83 | 7369440 | 64533984 |

Original native phase ceilings pass as metrics-only checks on API 37: cold 1500 ms, local 150 ms, Java 32 MiB/native 128 MiB. Companion timing includes holder setup, test-shell observation, Open/Save and teardown; it has no calibrated performance budget. Memory is 10 ms sampled absolute app Java/native heap, including initial/final samples; it excludes the separate viewer process and whole-device memory. Across native phases, maxima are Play 10969824/94741648 bytes and Zapstore 10949344/94753760 bytes (Java/native). Concurrent device activity and one sample per flavor limit inference.

Each qualified session: 4 GET / 0 HEAD (1 native cold acquisition plus 3 deliberately held fixture downloads), 1 upload, 4 fixture-control POSTs: 9 counted HTTP requests total. Successful response body writes total 99344 bytes =1040 native ciphertext  + 98304 held-fixture bytes; uploaded ciphertext is 1040 bytes separately. All acquisitions/uploads have completion events. No acquisition occurs after the endpoint is made unavailable, including Open/Save, ten retained reads and native database reopen. Counts exclude headers, error JSON, retransmits, and link traffic.

## Preserved failures and remaining gates

- Viewer setup initially omitted target SDK; Pixel rejected installation. Explicit min 30/target 36 fixed it without a bypass or uninstall. Earlier Kotlin source-set inclusion error was caught by DEX preflight before installation.
- Play sessions 1/2 are unqualified: receipt provider queries returned null before external Open; local materialization and cleanup passed. Exact earlier source/provenance versions remain archived.
- Play session 3 instrumentation and budgets passed, but the host checker incorrectly expected only three `held` events; the server emits repeated events per body chunk. Its original unqualified outcome is preserved/excluded. The corrected checker counts three distinct fixtures first held at 1024 bytes; only fresh session 4 qualifies.
- Baseline Play session1 hits the requested 5000 ms retained-materialization timeout while permits are held. The enclosing companion lasts 25601.46 ms until held sockets time out; only their first 1024 bytes each are written and cleanup reports failure. Owned adb mappings were removed. This unqualified negative control is excluded; it does not qualify baseline cancellation/cleanup or a five-second wall-clock duration.

The automated fixture qualifies the received resolver/materializer and platform Open/Save subcase of MED-023. It does not qualify the full MED-023 offline/lifecycle/lease-invalidations checklist or the broad MED-009/MED-017 bubble/picker release flows. Those excluded outcomes stay unqualified; the reduced local-before-network landing scope requires its own passing correctness, bandwidth, device and review/CI gates, rather than every unrelated release-checklist scenario. Real native backlog/promotion, process restart/whole-device offline, account switch/removal/expiry, large files, APK install, and genuine outgoing retention remain unqualified. Native database reopen does not prove app process restart. Substantive CodeRabbit review, meaningful 80% docstrings and current-head CI remain required. Production code, shipping fixtures and regression tests are unchanged by this evidence update. No issue closure, merge or release.

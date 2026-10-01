# Attachment evidence manifest — 2026-10-01

[Gist archive](https://gist.github.com/mubarakcoded/04c9118c05de4308b149b76ce3fc9ebc) is durable, public, and has no CI expiry. The revision-pinned download is a base64-encoded ZIP; the prior 45 members retain their original bytes and formatting. New failed reports include explicit post-run annotations while preserving their measurements and unqualified outcomes. Keep this archive for the lifetime of tracker #2779.

Download: <https://gist.githubusercontent.com/mubarakcoded/04c9118c05de4308b149b76ce3fc9ebc/raw/0ad930df2773de461738fb37d92795059269c4ce/attachment-evidence-2026-10-01.zip.base64>
ZIP SHA-256: `b01a678d98814a67f97429d8ce3ef054a6daaf427c4bcf118aa5ad658f7d1f5e`

75 members; 426,193 member bytes; 121,866 ZIP bytes. Original 45 members remain byte-identical. The previous immutable revision also remains downloadable. Anonymous HTTP 200, ZIP digest and every member checksum were verified before repository copies were removed; the expanded platform archive was independently reviewed, explicitly approved for public publication, and verified anonymously before this manifest update. This trims current PR diffs; original commits remain in Git history. No private fixture database/blob or instrumentation transcript is included.

```bash
python3 tools/attachment-fixture/verify_evidence.py \
  docs/performance-data/attachment-evidence-manifest-2026-10-01.md \
  --extract /private/tmp/attachment-evidence-verified
```

The verifier uses no credentials, rejects incomplete membership/corruption, and requires a fresh private extraction root (existing directories/symlinks are rejected).

Session source is baseline/candidate; provenance/check records can cover both. Foundation baseline1–3 calibrates budgets; comparison baseline4–6 alternates with candidate1–3. Partial sessions are excluded from that comparison even where their transport assertions passed. `setup-failures.json` retains both insufficient-storage attempts; the negative control retains the baseline timeout. Initial HTTP1.0/receipt-lookup development failures and the stalled Gradle worker have narrative records in the baseline report, but no raw transcripts in this archive.

CI-reference: run 36886299126 / PR #2978 head `5792f140880f533491d76f4b8cb14f944fc31026`; CI-candidate: run 36887456880 / PR #2980 head `a16731672f3845996a8953c3661cd3407cc9ddd6`. These API34 x86_64 reports are supplemental environment calibration; CI merge checkout/APK digests are not recorded in them. They are not pooled with the API30 arm64 comparison, which has exact source/APK provenance. Host-only results are Python-to-Python and cannot qualify Android throughput.

Pixel API37 evidence: twelve paired small-file sessions (three baseline/candidate per flavor), exact measured sources/APK digests and private-driver checksum in `pixel-2026-10-01/provenance.json`. API37 checks apply the original ceilings to metrics only; they do not assert an emulator environment profile. Device package snapshots changed during the window, so whole-device unchanged state or exclusive-device latency cannot be claimed. See the [physical report](attachment-pixel-2026-10-01.md).

Pixel platform companion: two final qualified sessions, three excluded diagnostic attempts, one unqualified baseline negative control with teardown failure, setup failures and exact reusable overlays. See the [platform report](attachment-pixel-platform-2026-10-01.md). Private backups, signing keys, APKs and raw instrumentation transcripts remain excluded.

| Original path inside ZIP | Evidence | Source | Flavor | Outcome | Bytes | SHA-256 |
| --- | --- | --- | --- | --- | ---: | --- |
| `ci-candidate/received-Play.json` | Android | candidate | Play | qualified | 4973 | `639f0d5a64f9999c1d45d1826add16108608fc48dd26158cffbac6fe5d1457c2` |
| `ci-candidate/received-Zapstore.json` | Android | candidate | Zapstore | qualified | 4959 | `1cf70e561561079c5ebf853b7d9c7984cd82d3d534c85e2cca7862ae7163c5c6` |
| `ci-reference/received-Play.json` | Android | baseline | Play | qualified | 4971 | `c13f8a26559e1cfbda5f6ff1b3e14a15ebec1fd0dd9869f4fa838af5cac88d63` |
| `ci-reference/received-Zapstore.json` | Android | baseline | Zapstore | qualified | 4961 | `0fb93563c9e9df7d88cc9be8b286e1bf7b72c00ea060bc33b1b6224a8a7ccc08` |
| `docs/performance-data/attachment-fixture-2026-10-01/baseline-play-1.json` | Android | baseline | Play | qualified | 4967 | `b635105c74c49528c85e0435b914e82084917c018088e547d0aa1bb7b109fd69` |
| `docs/performance-data/attachment-fixture-2026-10-01/baseline-play-2.json` | Android | baseline | Play | qualified | 4968 | `e200fbe0970c71c1a16de02c904c83df6a6abd115606b318c8af6e010c8f1fa9` |
| `docs/performance-data/attachment-fixture-2026-10-01/baseline-play-3.json` | Android | baseline | Play | qualified | 4960 | `5d274c61d9e6a1ed6937a015322c3e9088b84130ae5404b3d0d6ae91256095d4` |
| `docs/performance-data/attachment-fixture-2026-10-01/baseline-zapstore-1.json` | Android | baseline | Zapstore | qualified | 4958 | `ec4ea1ccf3f24dcaee7028da2689d3f90e7dd04feeb83de12d9354c5684b1b21` |
| `docs/performance-data/attachment-fixture-2026-10-01/baseline-zapstore-2.json` | Android | baseline | Zapstore | qualified | 4963 | `552d34733770dc0f3f00a26198b6df1c4d9d7e13fbd6564ae851ee328ba2ee67` |
| `docs/performance-data/attachment-fixture-2026-10-01/baseline-zapstore-3.json` | Android | baseline | Zapstore | qualified | 4960 | `8873d0eea8b808ddbb82b53ea6abe09e3b7e272da2bc289f04c71c42376b7cb0` |
| `docs/performance-data/attachment-fixture-2026-10-01/provenance.json` | provenance | mixed | both/host | metadata | 1989 | `fbbf9b77f46bb28b2779d2d3bcc4705b599f21436dc652d62dd9891c32fb9ce1` |
| `docs/performance-data/attachment-fixture-host-baseline-2026-10-01.json` | host-only | baseline | both/host | reference-pass | 10699 | `2e690046a5080f450a0ba5e4c871e649c277db3823555fef7a6e2c2bd7ab5448` |
| `docs/performance-data/attachment-local-first-2026-10-01/baseline-play-4.json` | Android | baseline | Play | qualified | 4947 | `1c1bf145dd7041d90e3f1a6c943e26dfcd70a2d730bbe5c1f2decd789d76d263` |
| `docs/performance-data/attachment-local-first-2026-10-01/baseline-play-5.json` | Android | baseline | Play | qualified | 4951 | `6190b9713d45fbfa0158c3639c8816fbd75b83365c4b727f8ea6a2effef2304f` |
| `docs/performance-data/attachment-local-first-2026-10-01/baseline-play-6.json` | Android | baseline | Play | qualified | 4957 | `69c4db46f12768084d0a63d6d713a8ba1ac609242aaca0946e19e8891e3c43ec` |
| `docs/performance-data/attachment-local-first-2026-10-01/baseline-zapstore-4.json` | Android | baseline | Zapstore | qualified | 4946 | `1844fca8bea1870c3f531ff377a519a814dfc4916975f964a76582868ff4bef9` |
| `docs/performance-data/attachment-local-first-2026-10-01/baseline-zapstore-5.json` | Android | baseline | Zapstore | qualified | 4949 | `febc3a15e706cb3750fbcf59241d782436c7ce25341af3208a4f08b7557fc9fb` |
| `docs/performance-data/attachment-local-first-2026-10-01/baseline-zapstore-6.json` | Android | baseline | Zapstore | qualified | 4951 | `c536aaeb3f9d6b2bfe3e671f5b8e34afca914f9054fd52dbfe5c5fa34015e5a4` |
| `docs/performance-data/attachment-local-first-2026-10-01/budget-checks.json` | checks | mixed | both/host | reference-pass | 2565 | `7b5824c11bff4578a11a792d3713071f3d6585e979f23cf14ee43679f13dffaa` |
| `docs/performance-data/attachment-local-first-2026-10-01/candidate-play-1.json` | Android | candidate | Play | qualified | 4954 | `615226ec9d620467194a41b3b40e060367bdfd1800f9dd3f6b7582545a5f7812` |
| `docs/performance-data/attachment-local-first-2026-10-01/candidate-play-2.json` | Android | candidate | Play | qualified | 4945 | `320afd3826ff6f401b9966315a1480cb710a096fbd6ebbdf6a75529369fc1bcd` |
| `docs/performance-data/attachment-local-first-2026-10-01/candidate-play-3.json` | Android | candidate | Play | qualified | 4952 | `5a9095e95281c9c40346cf357235b36a05379e3afb59b95104b33917d1048b3d` |
| `docs/performance-data/attachment-local-first-2026-10-01/candidate-zapstore-1.json` | Android | candidate | Zapstore | qualified | 4943 | `4a9dea5057c6f4103c97f7e584984f908e13a5097ede2bd405260d944ee672a1` |
| `docs/performance-data/attachment-local-first-2026-10-01/candidate-zapstore-2.json` | Android | candidate | Zapstore | qualified | 4947 | `4555b3834a93bb436cf36d5aa53a6a7f00648500f2aaae4e8f7593afa302be63` |
| `docs/performance-data/attachment-local-first-2026-10-01/candidate-zapstore-3.json` | Android | candidate | Zapstore | qualified | 4949 | `c3e937185247ec07ffcb640af0876dfc850a8f905a835f49f331bf7c06be6883` |
| `docs/performance-data/attachment-local-first-2026-10-01/provenance-play.json` | provenance | mixed | Play | metadata | 1859 | `9cc0d2393f31246fd1c195c47d500fdc9595e71e4db571abc6ee328435c5a976` |
| `docs/performance-data/attachment-local-first-2026-10-01/provenance-zapstore.json` | provenance | mixed | Zapstore | metadata | 1852 | `1fa0b6474f772873ce115361ea94661a07f4d708c61887dc680f110b1a9a44e9` |
| `docs/performance-data/attachment-local-first-2026-10-01/saturated-gate-regression.json` | checks | mixed | both/host | negative-control+pass | 745 | `a30cbb9b0b28a7628d48486cacba54350e5c9f8632ed043ed2901dcda77e7fae` |
| `docs/performance-data/attachment-local-first-2026-10-01/setup-failures.json` | failure | mixed | both/host | setup-failed | 531 | `4f7e4ca57eeef8d1df7a1f6f5de8dd332dd608255d3c4c022ef713c01a9f100f` |
| `docs/performance-data/attachment-local-first-2026-10-01/setup-partial/baseline-zapstore-universal.json` | Android | baseline | Zapstore | partial/excluded | 4949 | `20bf35e3614634b2039bc23c717d4f83a310e46241f33adf17f52eb0afc079f0` |
| `docs/performance-data/attachment-local-first-2026-10-01/setup-partial/candidate-zapstore-arm64-old-emulator.json` | Android | candidate | Zapstore | partial/excluded | 4950 | `4031a132c2a6cdcb4d6b0e10dd9470ace3e89a6ae67ac9b7e6c8395cf7392db5` |
| `docs/performance-data/attachment-local-first-2026-10-01/setup-partial/candidate-zapstore-universal.json` | Android | candidate | Zapstore | partial/excluded | 4952 | `6dd1eaa04fb06280ae9d66c0bcf8b6e9b6470d1af22952a25e279e4d910ba429` |
| `pixel-2026-10-01/baseline-Play-1.json` | Android physical | baseline | Play | qualified-small-file | 5943 | `ea62ef5d7702511ab60ebc6a220787f8612e5af00d24ac468b5012e50c2440d7` |
| `pixel-2026-10-01/baseline-Play-2.json` | Android physical | baseline | Play | qualified-small-file | 5941 | `688b467d15543b856f7a3e07f8d4ba5cc5c55921ba28179e74cbba24f41dcb58` |
| `pixel-2026-10-01/baseline-Play-3.json` | Android physical | baseline | Play | qualified-small-file | 5944 | `b147610bd5c97c8a4fe45934cec1b0851409e5f2d86cd25eeba2b8f3bdd0c26b` |
| `pixel-2026-10-01/baseline-Zapstore-1.json` | Android physical | baseline | Zapstore | qualified-small-file | 5947 | `a3db76c7f4d929a145e4548963161cdc092b36723dc9fc50483347c208727da9` |
| `pixel-2026-10-01/baseline-Zapstore-2.json` | Android physical | baseline | Zapstore | qualified-small-file | 5946 | `8a2d0f27c8b59d9a667b6bea7e494b5d4867764ea36c1bfd986ef4d49f8aa807` |
| `pixel-2026-10-01/baseline-Zapstore-3.json` | Android physical | baseline | Zapstore | qualified-small-file | 5947 | `d632690b02ddcb62c491bb45e4b680f87fe6b01ec0d8a2025532b772c1b87c9e` |
| `pixel-2026-10-01/candidate-Play-1.json` | Android physical | candidate | Play | qualified-small-file | 5943 | `2fe815361729e61984a8ad28ffd74be85297aab5b04abeb53bb5d1f4fd5bf57a` |
| `pixel-2026-10-01/candidate-Play-2.json` | Android physical | candidate | Play | qualified-small-file | 5940 | `bb4d5d403835b89fb487fa11e12cd1af59290f8b95733caccdd184a071b50188` |
| `pixel-2026-10-01/candidate-Play-3.json` | Android physical | candidate | Play | qualified-small-file | 5942 | `252d4535cb5af9f0890b676f1fb0ffff4b786d39d17ad0134696f5ce5999d661` |
| `pixel-2026-10-01/candidate-Zapstore-1.json` | Android physical | candidate | Zapstore | qualified-small-file | 5945 | `4077d33c65accf7261fdea62dc46115cfc6515d1985466c8c8bfd719f805ecfe` |
| `pixel-2026-10-01/candidate-Zapstore-2.json` | Android physical | candidate | Zapstore | qualified-small-file | 5947 | `bd579eade621cbb2e8441322ed149541f47575dd9e5dd447a780b7cc6367329b` |
| `pixel-2026-10-01/candidate-Zapstore-3.json` | Android physical | candidate | Zapstore | qualified-small-file | 5946 | `7059d6dff61bc6689bd5d3bcda178501465bc40bca25e6be14a42de9c4f8fb49` |
| `pixel-2026-10-01/provenance.json` | provenance | mixed | both | metadata/concurrent-device | 3654 | `bac34eb43f93219fd480fe28d942a10dd670684d6fd7238b9ebd54fbe08c28b0` |
| `pixel-platform-2026-10-01/apk-provenance.json` | provenance | mixed | both | metadata | 1861 | `d298e6146dcca3a56721c3e16243e076278fc5b419abfb9f63c944193099a1f1` |
| `pixel-platform-2026-10-01/diagnostic-README.md` | diagnostic source | mixed | both | reusable-overlay | 3504 | `8dd073dd139b4d39f407cd8321d834f019eaa05301eeeb462d942e34e7a3f97b` |
| `pixel-platform-2026-10-01/diagnostic-src/AndroidManifest.xml` | diagnostic source | mixed | both | reusable-overlay | 1510 | `803c4c893360c2cfc5c62bedc6287b6ff21abfa5068191b271be05d0be16262e` |
| `pixel-platform-2026-10-01/diagnostic-src/LocalFirstDeviceChecks.kt` | diagnostic source | mixed | both | reusable-overlay | 12395 | `b85cb8d5ae43392ed2739d94fe9f023c9f7e5b5b5c253ee8512baa7afa6f0dea` |
| `pixel-platform-2026-10-01/diagnostic-src/LocalFirstDeviceFixture.kt` | diagnostic source | mixed | both | reusable-overlay | 15403 | `c90d4bf4b0b8913ea500775a6a0d046f98496fdecebcc5471027c081b99f3d16` |
| `pixel-platform-2026-10-01/failed-fixture-v1/apk-provenance.json` | diagnostic source | candidate | Play | unqualified-source-preserved | 1766 | `9dfbd97a5dddadeb68022ff0313474fe45f6042834e3d7f32c7c9ba71176af78` |
| `pixel-platform-2026-10-01/failed-fixture-v1/diagnostic-src/LocalFirstDeviceChecks.kt` | diagnostic source | candidate | Play | unqualified-source-preserved | 12112 | `8878e6960b4bfc17d8562e61306da1da1bb26bb388a6d7c6cfa3d4afd019ca54` |
| `pixel-platform-2026-10-01/failed-fixture-v1/diagnostic-src/LocalFirstDeviceFixture.kt` | diagnostic source | candidate | Play | unqualified-source-preserved | 15403 | `c90d4bf4b0b8913ea500775a6a0d046f98496fdecebcc5471027c081b99f3d16` |
| `pixel-platform-2026-10-01/failed-fixture-v1/platform-companion.init.gradle` | diagnostic source | candidate | Play | unqualified-source-preserved | 341 | `03b5d2e50247ccef2afd59ca8c671a971efba1df2f7897cbd7add24eaec2cbbd` |
| `pixel-platform-2026-10-01/failed-fixture-v1/platform_host_driver.py` | diagnostic source | candidate | Play | unqualified-source-preserved | 9387 | `6bd58e1d4e7993dc4bd4cd5e303ff679c8e624a183833dc6537c23ab224d5198` |
| `pixel-platform-2026-10-01/failed-fixture-v2/apk-provenance.json` | diagnostic source | candidate | Play | unqualified-source-preserved | 1861 | `4d7b5238883ce8453f9e1d032898af811e4c7f87e72bf97afbd0a9e99f199f44` |
| `pixel-platform-2026-10-01/failed-fixture-v2/diagnostic-src/AndroidManifest.xml` | diagnostic source | candidate | Play | unqualified-source-preserved | 1510 | `803c4c893360c2cfc5c62bedc6287b6ff21abfa5068191b271be05d0be16262e` |
| `pixel-platform-2026-10-01/failed-fixture-v2/diagnostic-src/LocalFirstDeviceChecks.kt` | diagnostic source | candidate | Play | unqualified-source-preserved | 12166 | `db87979d2595a23377a873676b5c25f9a3f4bb09689c6e71e287d56ee9ff849f` |
| `pixel-platform-2026-10-01/failed-fixture-v2/diagnostic-src/LocalFirstDeviceFixture.kt` | diagnostic source | candidate | Play | unqualified-source-preserved | 15403 | `c90d4bf4b0b8913ea500775a6a0d046f98496fdecebcc5471027c081b99f3d16` |
| `pixel-platform-2026-10-01/failed-fixture-v2/platform-companion.init.gradle` | diagnostic source | candidate | Play | unqualified-source-preserved | 495 | `04bf0674e76d60ba99b9e22a56a0749bc05e15bb84e23787fad553cd5025d36f` |
| `pixel-platform-2026-10-01/failed-fixture-v2/platform_host_driver.py` | diagnostic source | candidate | Play | unqualified-source-preserved | 9438 | `85a99d256a60281c165fc4002dbc3839f5dbc15392aa49d2dc0fe5a3bb3faa38` |
| `pixel-platform-2026-10-01/failed-fixture-v3/apk-provenance.json` | diagnostic source | candidate | Play | unqualified-source-preserved | 1861 | `854dc4e3486b54030a169c23556dc3e525d735d0b3bfaece93263babb4f354a4` |
| `pixel-platform-2026-10-01/failed-fixture-v3/platform_host_driver.py` | diagnostic source | candidate | Play | unqualified-source-preserved | 9438 | `85a99d256a60281c165fc4002dbc3839f5dbc15392aa49d2dc0fe5a3bb3faa38` |
| `pixel-platform-2026-10-01/platform-companion.init.gradle` | diagnostic source | mixed | both | reusable-overlay | 495 | `04bf0674e76d60ba99b9e22a56a0749bc05e15bb84e23787fad553cd5025d36f` |
| `pixel-platform-2026-10-01/platform_host_driver.py` | diagnostic source | mixed | both | reusable-overlay | 9470 | `2c790f1bfabda3510b2876fea1d2c65b3b54bdb5fd2b1bab534038cf2b20c9d0` |
| `pixel-platform-2026-10-01/reports/baseline-Play-1.json` | Android physical | baseline | Play | negative-control/teardown-failed | 8816 | `79fb48508bd6b877535799ec7cd6c4d824f141dc2ab87c135680edbd9bfeabb8` |
| `pixel-platform-2026-10-01/reports/candidate-Play-1.json` | Android physical | candidate | Play | unqualified/excluded | 9849 | `4daab3e2b35ffaacbf51ba9aee10e78385ec091cf8e9791b6d40c7f25c7b18cc` |
| `pixel-platform-2026-10-01/reports/candidate-Play-2.json` | Android physical | candidate | Play | unqualified/excluded | 9948 | `4143f17ee7f0985e5407f80a3cb9173a28972cae1dbc56fa9770bf296be762f3` |
| `pixel-platform-2026-10-01/reports/candidate-Play-3.json` | Android physical | candidate | Play | unqualified/excluded | 12846 | `290af19ebff1cdb3719a122cd2da6b29b00378c1e6e4f5d5b5dc91a19531d0c0` |
| `pixel-platform-2026-10-01/reports/candidate-Play-4.json` | Android physical | candidate | Play | qualified-platform-companion | 12493 | `e0d3fa23094056cf2ed75b699803a2e58405e3e7df75b6e8ec1323966abe1caf` |
| `pixel-platform-2026-10-01/reports/candidate-Zapstore-1.json` | Android physical | candidate | Zapstore | qualified-platform-companion | 12495 | `e81b41b5c7e9c3ce808507401ee2dd2926cc21f3905c2422b9fbd7506fe1cbc7` |
| `pixel-platform-2026-10-01/reports/setup-failures.json` | failure | mixed | both | setup-failed | 613 | `9b53f6c5e6fe92e76de1f8d13a714eb02f1635214c7eee4d2c9e85f28762d114` |
| `pixel-platform-2026-10-01/viewer/AndroidManifest.xml` | diagnostic source | mixed | both | reusable-overlay | 541 | `f76d9a709fc1ba4fb1cf37e20c4a0f1a366139e2c7c707994fab4b35458dfc1b` |
| `pixel-platform-2026-10-01/viewer/src/FixtureStatusProvider.java` | diagnostic source | mixed | both | reusable-overlay | 1761 | `2339b5ae5d1a172a5e168e973dc644f3662553980daafdd53cf02ba9da8c1129` |
| `pixel-platform-2026-10-01/viewer/src/FixtureViewerActivity.java` | diagnostic source | mixed | both | reusable-overlay | 1905 | `e294a139bfa5aec4dce74f441b0aa178704fa9218397bf957be880408aee69d1` |

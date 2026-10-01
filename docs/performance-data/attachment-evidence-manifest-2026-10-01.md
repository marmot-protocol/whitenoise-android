# Attachment evidence manifest — 2026-10-01

[Gist archive](https://gist.github.com/mubarakcoded/04c9118c05de4308b149b76ce3fc9ebc) is durable, public, and has no CI expiry. The revision-pinned download is a base64-encoded ZIP; all JSON members retain their original bytes and formatting. Keep this archive for the lifetime of tracker #2779.

Download: <https://gist.githubusercontent.com/mubarakcoded/04c9118c05de4308b149b76ce3fc9ebc/raw/8a8b7cbb5e5e12a6626cd1d7df6a50d1622de38e/attachment-evidence-2026-10-01.zip.base64>
ZIP SHA-256: `ea49d9c0b017005fe8fca7f8688e31ebd7d4f77e09208665927b2b751a00f2ff`

45 members; 219,107 original bytes; 58,308 ZIP bytes. Original 32 members remain byte-identical. The previous immutable revision also remains downloadable. Anonymous HTTP 200, ZIP digest and every member checksum were verified before repository copies were removed; the expanded Pixel archive was independently reviewed and verified anonymously before this manifest update. This trims current PR diffs; original commits remain in Git history. No private fixture database/blob or instrumentation transcript is included.

```bash
python3 tools/attachment-fixture/verify_evidence.py \
  docs/performance-data/attachment-evidence-manifest-2026-10-01.md \
  --extract /private/tmp/attachment-evidence-verified
```

The verifier uses no credentials, rejects incomplete membership/corruption, and requires a fresh private extraction root (existing directories/symlinks are rejected).

Session source is baseline/candidate; provenance/check records can cover both. Foundation baseline1–3 calibrates budgets; comparison baseline4–6 alternates with candidate1–3. Partial sessions are excluded from that comparison even where their transport assertions passed. `setup-failures.json` retains both insufficient-storage attempts; the negative control retains the baseline timeout. Initial HTTP1.0/receipt-lookup development failures and the stalled Gradle worker have narrative records in the baseline report, but no raw transcripts in this archive.

CI-reference: run 36886299126 / PR #2978 head `5792f140880f533491d76f4b8cb14f944fc31026`; CI-candidate: run 36887456880 / PR #2980 head `a16731672f3845996a8953c3661cd3407cc9ddd6`. These API34 x86_64 reports are supplemental environment calibration; CI merge checkout/APK digests are not recorded in them. They are not pooled with the API30 arm64 comparison, which has exact source/APK provenance. Host-only results are Python-to-Python and cannot qualify Android throughput.

Pixel API37 evidence: twelve paired small-file sessions (three baseline/candidate per flavor), exact measured sources/APK digests and private-driver checksum in `pixel-2026-10-01/provenance.json`. API37 checks apply the original ceilings to metrics only; they do not assert an emulator environment profile. Device package snapshots changed during the window, so whole-device unchanged state or exclusive-device latency cannot be claimed. See the [physical report](attachment-pixel-2026-10-01.md).

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

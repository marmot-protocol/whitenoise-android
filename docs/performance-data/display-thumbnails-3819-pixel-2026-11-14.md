# Persistent display thumbnails: Pixel 9a baseline

Measurements precede the cache-format/budget decision. This is an implementation
baseline, not completed feature qualification or a release performance claim.

Device: Pixel 9a, Android 17/API 37, GrapheneOS `2026110201`. Existing debuggable
Dev APK source `6481a2580b9f9455465b2868ae61de737e437df3`, APK SHA-256
`76feddde66021e3a097f2ce68942999858f9199bcff1e4df7cbdd0233b58cf48`.
Its MDK revision is `a2e0558a634bbd20607cdd5afb93abc6220ee58d`, matching the
implementation base's MarmotKit 0.16.0 runtime. No uninstall, storage clear,
database modification or APK replacement was performed. Existing app data was
backed up privately and verified before the cold-start runs. Raw diagnostics and
recordings remain private; this document contains synthetic-fixture aggregates.

## Received-image reproduction

A dedicated two-profile test group received generated textured-gradient JPEGs.
The source corpus has 48 distinct 1600×1200/1200×1600 images, 60,087,344 bytes
in total. The file collection stalled after eight sends; requests around invite
acceptance also returned native UNAVAILABLE. Explicit retries subsequently
rendered images. These first-download failures are not thumbnail-cache results.

Three visible received images were confirmed to render after a full process stop
with both Wi-Fi and mobile data disabled. Both settings were restored after each
measurement sequence. Matching a grid of RGB samples inside each tile against
its fully rendered synthetic reference gave these two valid cold-open observations:

| Trial | Last sample without real pixels | First sample matching all three images |
| --- | ---: | ---: |
| 1 | 4.066 s | 5.491 s |
| 2 | 10.422 s | 11.922 s |

Times start before the ADB tap command and include chat opening, not only decode.
Capture intervals were approximately 1.3–1.5 seconds. They are observation bounds,
not precise first-frame timings or percentiles. A third trial remained on the chat
list and is invalid, not a 40-second image failure. Screen recording did not capture
a complete reveal reliably. A method trace overflowed; no original-read or native
request counts are claimed. One cold-run memory sample was 402,708 KiB PSS and
610,424 KiB RSS; it is not a peak measurement.

Natural scroll eviction and albums remain unqualified: the transfer did not finish
populating the intended corpus. An attempted explicit background trim was rejected
by Android's foreground-process check and provides no eviction evidence.

## Codec component experiment

A standalone Java/DEX process on the same Pixel decoded only generated fixture
files. It scaled four sources to each edge, compressed them, wrote an AES-GCM
payload with an ephemeral software key and fsync, then read/decrypted/decoded it.
Each source/edge/format had three repetitions. This does not include Android
Keystore, MDK reads, isolated-worker admission, cold filesystem I/O or Compose.
There was concurrent app activity; these results establish format tradeoffs,
not stable performance budgets. The textured synthetic corpus is not a visual
quality assessment of real photographs.

Median opaque-image results (12 samples per cell):

| Edge | Format | Encoded bytes | Encode | Warm read/decrypt/decode |
| --- | --- | ---: | ---: | ---: |
| 384 | JPEG 85 | 30,945 | 1.62 ms | 1.49 ms |
| 384 | WebP lossy 85 | 32,911 | 18.35 ms | 2.62 ms |
| 768 | JPEG 85 | 115,322 | 5.58 ms | 3.12 ms |
| 768 | WebP lossy 85 | 124,275 | 63.97 ms | 9.41 ms |
| 768 | WebP lossless | 920,102 | 26.35 ms | 14.84 ms |
| 768 | PNG | 930,422 | 52.49 ms | 11.05 ms |
| 1024 | JPEG 85 | 204,298 | 9.08 ms | 6.03 ms |
| 1024 | WebP lossy 85 | 225,210 | 116.36 ms | 18.82 ms |
| 1024 | WebP lossless | 1,556,774 | 51.43 ms | 29.16 ms |
| 1024 | PNG | 1,636,527 | 95.47 ms | 22.65 ms |

A separate maximum-edge pass used two opaque sources and two sources with a
checkerboard alpha mask (six samples per cell). At 1280 px, opaque JPEG 85 used
311,015 bytes and 33.44 ms median encode, versus 340,145 bytes/408.90 ms for lossy
WebP. For alpha, lossless WebP used 2,445,051 bytes/283.26 ms versus PNG's
2,929,455 bytes/849.12 ms. Lossy formats are not candidates for alpha preservation.

## Draft policy and remaining qualification

Use JPEG 85 for opaque pixels and lossless WebP for alpha, after the existing
isolated decoder has admitted a static image. Retain the requested decode ceiling
without upscaling small originals. Keep GIF, WebP and ambiguous source MIME types
on the existing animation-aware path. Encode off main and retain no unbounded queue
of shared bitmaps; a busy writer may skip an expendable derivative.

Start with a 32 MiB encrypted LRU, separate from the 256 MiB original cache. At the
measured 1280-px opaque size this holds about 100 entries; the 48-source fixture
would use about 15 MiB at that size. Transparent/noisy lossless entries consume
more space and evict sooner. The fixed cap is a conservative starting policy,
not a device-derived optimal working-set size. Account/chat/message/attachment,
size, source epoch and ciphertext identity are checked before reuse.

Before readiness, finish the received-photo/album fixture, measure natural memory
eviction and repeated offline cold opens on the exact candidate, collect reliable
original-read/transfer counts and peak memory, inspect real-photo quality, and
exercise clear/removal/expiry/account races and original Open/Save/Share. Compare
matching APK provenance and native pins. The first-download scheduling follow-up
remains blocked on MDK #3417 merging and Android adopting a containing build.

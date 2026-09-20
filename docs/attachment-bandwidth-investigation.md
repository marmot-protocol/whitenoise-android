# Attachment bandwidth investigation, 2026-09-20

Status: **the cache-eviction loop is not fixed by this patch**. The implemented
change removes one additional WorkManager retry of a completed, unretained body.
Durable acquisition adoption remains a dependency of the complete fix. Product
tracking stays in [#2686](https://github.com/marmot-protocol/whitenoise-android/issues/2686),
with canonical transfer/control adoption in
[#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045).

## Source and root cause

Inspected live Android master `30ff0a63462f48dfb4971ed38247ca20ab48197d`,
which pins MarmotKit 0.10.3 / MDK
`7d8bba365def75a66774f2b2036f839411010001`. The earlier reproduction pinned
Android `4917a8276d7e2cea4a2f13caacbf7e15dc5d91db`.

The four complete production files in the reproduction manifest are byte-for-byte
unchanged on current master: `DiskByteCache`, `ByteSizeLruCache`,
`AttachmentTransferCoordinator`, and `StalenessGuard`. Android's download owner
still calls legacy `downloadMedia`; upgrading the binding did not migrate it.
[PR #2490](https://github.com/marmot-protocol/whitenoise-android/pull/2490)
rechecks cache misses after admission but cannot prevent a real eviction miss.

The 256 MiB encrypted attachment cache can retain six 40 MiB bodies. Publication
of a seventh evicts another body and emits a cache mutation. The file bubble
refreshes availability; the coordinator changes Available to Remote on a miss.
The bubble's automatic effect treats Remote plus allowed policy as permission
to acquire again. No durable successful-acquisition check occurs in
`downloadAttachmentPlaintext`, `memoizedDownload`, or the worker path. A cache
miss is incorrectly serving both as an availability result and as network
authorization. Retiring a controller or restarting the process also loses its
terminal presentation states.

Separately, `downloadAttachmentForDurableWork` can finish reading a complete
body and return false because no cache retains it. `AttachmentDownloadWorker`
previously converted false to `IOException`; the transient classifier admitted
one further full download. Cache publication failure, entry-size rejection, and
eviction before the final availability probe can all reach this result.

## Implemented change

The worker now raises the terminal `AttachmentNotRetainedException` for that
false result. It reports failure, clears interactive scheduling intent, and
does not schedule a transport retry. Real transient transport failures still
receive at most one retry. Cancellation propagates. Explicit subsequent work
can still run. No success record or fake user-cancellation marker is written.

This does **not** suppress a fresh automatic WorkManager generation, nor change
the foreground acquisition path. It must not be advertised as the seven-file
loop fix. No cache size, persistent protocol-data store, rendering, or screenshot
baseline changed. `MED-017` covers the changed failure/recovery behavior.

## Why the released MDK API is not a drop-in fix

Reviewed the source of the exact pinned artifact, particularly:

- [`ATTACHMENT-ACCESS.md`](https://github.com/marmot-protocol/mdk/blob/7d8bba365def75a66774f2b2036f839411010001/crates/marmot-uniffi/ATTACHMENT-ACCESS.md):
  local lookup/read never registers demand. Native acquisition defaults on,
  uses a separate retained quota, and does not automatically evict acquired
  assets. Native reads require original source-message identity.
- [`runtime/attachment_controls.rs`](https://github.com/marmot-protocol/mdk/blob/7d8bba365def75a66774f2b2036f839411010001/crates/marmot-app/src/runtime/attachment_controls.rs):
  `downloadAttachmentAgain` explicitly clears suppression/cancellation.
  `controlAttachment(Retry)` also expresses explicit demand. Neither is an
  atomic ordinary automatic request that preserves suppression and successful
  acquisition. The exposed automatic policy is account-wide, without Android's
  per-media-type/network admission.
- [`runtime/mod.rs`](https://github.com/marmot-protocol/mdk/blob/7d8bba365def75a66774f2b2036f839411010001/crates/marmot-app/src/runtime/mod.rs):
  legacy `download_media` delegates to the transient download path, without
  committing successful acquisition to the retained job store.
- [`account_worker/attachments.rs`](https://github.com/marmot-protocol/mdk/blob/7d8bba365def75a66774f2b2036f839411010001/crates/marmot-app/src/runtime/account_worker/attachments.rs):
  native admission distinguishes explicit requests from automatic ones, including
  their transfer limits. Calling the explicit API for every Android automatic
  request would change those semantics. Native completion also schedules another
  attempt if verified plaintext cannot be retained because of resource pressure;
  simply switching APIs does not establish Android's bounded-retry contract.

The integration needs a released, source-bound automatic-acquisition operation
that atomically preserves acquisition/terminal/removal state, admits only
host-approved demand, and exposes local absence separately from automatic
eligibility. Successful acquisition must survive byte eviction and process
restoration; failed/interrupted work must not become success. Source replacement,
explicit removal, and explicit download-again must have distinct transitions.
Policy admission must cover existing and newly created/imported accounts before
native automatic work can bypass Android controls. UI and restored workers must
use that same owner, with no legacy network fallback or parallel download queue.

This state belongs in MDK's SQLite ownership. An Android SharedPreferences set
of successful downloads (including repurposing user-cancellation records), an
in-memory map, or a Compose flag would violate the requested durability/source
of truth contract. Native source code should be changed and released, then the
consumer should adopt the reviewed immutable artifact according to
[`updating-marmotkit.md`](updating-marmotkit.md); a locally regenerated binding
cannot stand in for that release.

## Evidence and limits

The original evidence remains at `/home/jeff/repos/android-bandwidth-repro/`.
Its realistic 40 MiB fixtures and isolated `dev.ipf.bandwidthlab` installation
were preserved. SHA-256 of the retained result files:

```text
component.jsonl
d8f7a16a193a2a65ad64eae7f7573006cbda9564e9741a259f824f7647592b89
device-verified-summary.json
b63d7470d02cc9630a66c85e29bcd9df0a8c84caa0a6b5684434ebc0957b1b2c
```

| Original scenario | Component GETs | Pixel lab/server GETs |
| --- | ---: | ---: |
| Six files, 256 MiB | 6, settled | 6, settled |
| Seven files, 256 MiB | 42, safety cutoff | 21, safety cutoff |
| Seven files, 320 MiB | 7, settled | 7, settled |
| Automatic disabled | 0 | 0 |

The device pressure case transferred 840 MiB with seven rows continuously
composed, without scrolling/tapping. This was a separate Compose lab with
synthetic plaintext HTTP over USB, not the full production screen, WorkManager,
MDK cryptography, or real accounts. No new device transfer was run for the
worker-only patch, and no existing White Noise installation/data was changed.
There is no passing after-fix seven-file result to report.

`AttachmentDownloadWorkerClassTest` exercises the actual worker boundary with
counted synthetic completion callbacks; it does not measure payload bytes,
transfer 40 MiB over HTTP, or validate native acquisition. Its tests
cover terminal non-retention for both priorities, explicit subsequent success,
bounded transient retries, and cancellation propagation. Navigation/restoration,
UI/worker durable eligibility, source replacement/removal, and native policy
admission remain unvalidated for the complete fix.

The reported 1 TB/month and 15 GB in hours remain historically unattributed.
The reported retained Goggles uploads total 435.4 MB for September 11–19; this
weakens that explanation for the interval without measuring missing/failed
uploads. Neither the lab nor this worker change attributes those incidents.

## Validation of this patch

Passed with the repository's pinned Gradle/MarmotKit artifacts and JDK 21:

```sh
./gradlew \
  :app:testDevZapstoreDebugUnitTest --tests '*AttachmentDownload*' \
  :app:testDevPlayDebugUnitTest --tests '*AttachmentDownload*' \
  :app:ktlintCheck :app:detekt \
  :app:lintDevZapstoreDebug :app:lintDevPlayDebug
python3 scripts/check_manual_test_guide.py
python3 -m unittest scripts/test_check_manual_test_guide.py
git diff --check
```

Each flavor ran 64 download tests across seven classes, with zero failures,
errors, or skips; the worker class accounts for 14 tests in each flavor. The
guide validator checked 260 active IDs, and its 33 Python tests passed. Both
flavors' production and unit-test sources compiled. This is focused validation,
not a full unit-suite, screenshot-suite, or production-app device pass.

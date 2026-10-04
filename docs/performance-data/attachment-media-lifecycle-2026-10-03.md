# Genuine media retention across an Android process restart, 2026-10-03

Refs [#2909](https://github.com/marmot-protocol/whitenoise-android/issues/2909) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). This report qualifies the native and
host-cache layers for genuine sent and received images, videos and an album across an Android process restart with
acquisition unavailable. It changes no shipping behavior and makes no performance claim.

## Method

`controller-media` runs two separately launched instrumentation processes against generated peers over loopback.

**Prepare process.** Five messages are sent through the shipping `ConversationController.sendAttachments` path: a
generated JPEG, the bundled 10 second H.264/AAC clip, that clip padded to 9 MiB (above the 8 MiB memory-entry
ceiling) and to 24 MiB, and a three-attachment album of two JPEGs and the clip. Nothing is seeded. For each send the
probe waits for the shipping own-copy publication to its encrypted host cache, reads the sender's native copy before any
receiver acts, then downloads every attachment once as the receiver. The 24 MiB send also holds its host copy and must
read exactly through native retention before it can finish, which proves retention does not wait for the host copy.
Acquisition is then made unavailable.

**Read process.** The runner force-stops only the isolated fixture package. A new process opens the restored native
runtime. For both the sending and receiving account and every attachment it requires: a memory-cache miss, an exact
SHA-256 read from native retention, an exact read through the unchanged resolver, and a decoded first frame (a bitmap
for an image, a frame and duration for a video). For the album it also requires distinct plaintext at each index under
one logical message identity.

The server ledger is authoritative. `media_checker.py` requires one upload and one successful acquisition per
attachment before the restart boundary, matching ciphertext byte counts, and **no** GET or HEAD after it. It records
`performance_qualified: false`.

## Source cohort

- Signed commit `cc0f9ee911768d730aa09496d3a1c58cff19c6e4` on the head of #3025, which rests on #3021 and master
  `60988a94eb4c431fb35e36251d4aa60ac5204218`. The tree was clean and committed when each case was built.
- MDK pin `122bd90ffac60bb6311346e228d0f609a18521ee`, native bytes unchanged.
- Owned AVDs only: `wn_2779_fixture_api30` and `wn_2779_fixture_api36`, both arm64-v8a.

| Case | App APK SHA-256 | Test APK SHA-256 |
| --- | --- | --- |
| Play | `20c720d954dfa76018b6394cb94df8c49792367d53bf06ce31bff9bb2cb379c7` | `72d0af2ab32ec8f0dcdc5772a7c849bd511f0c8ef26631685cc6d2cb2d08f1e3` |
| Zapstore | `05d3d0af3aa2b41e753588a2c496e3cfc4e1962fd6e153297414296e0abc8b1d` | `eaeb48ee08097d0c2f4b45642d7fc7a4fd430c23b837f43655d550078f15444f` |

The same APK pair ran on both API levels for each flavor.

## Results

All four cases pass the checker, the ledger and the API/ABI environment check. Each made 7 uploads and 7 GETs, wrote
the same ciphertext it uploaded, and made **0** acquisitions after the restart.

Received copies after restart on API 30 Play (milliseconds; the read process's own timings, not budgets):

| Attachment | Bytes | Native read | Resolver read | First-frame decode | Own host copy survived |
| --- | ---: | ---: | ---: | ---: | --- |
| single-image#0 | 16,051 | 3.6 | 4.3 | 4.4 | yes |
| video-small#0 | 19,832 | 3.0 | 3.0 | 32.4 | yes |
| video-9mib#0 | 9,437,184 | 66.1 | 62.8 | 83.9 | yes |
| video-24mib#0 | 25,165,824 | 381.1 | 366.3 | 371.8 | no |
| album-3#0 | 16,051 | 1.8 | 2.0 | 3.3 | yes |
| album-3#1 | 16,057 | 1.4 | 1.8 | 3.2 | yes |
| album-3#2 | 19,832 | 1.8 | 2.0 | 29.4 | yes |

The 24 MiB video on every case:

| Case | Native read ms | Resolver read ms | Decode ms | Java peak MiB | Native peak MiB | GETs total / after restart |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| API 30 Play | 381 | 366 | 372 | 13 | 79 | 7 / 0 |
| API 30 Zapstore | 359 | 353 | 375 | 13 | 79 | 7 / 0 |
| API 36 Play | 360 | 371 | 380 | 8 | 103 | 7 / 0 |
| API 36 Zapstore | 374 | 376 | 397 | 9 | 102 | 7 / 0 |

## What this shows

- Sent and received images, videos and album members stay locally readable across a process restart with no
  acquisition, including media above the 8 MiB memory-entry ceiling.
- Every own send publishes its encrypted host copy, and that copy survives the restart, so a returning own tile has a
  host-cache hit. The 24 MiB case is the one exception by design, because it holds its copy in a root-local cache.
- In this probe the received copies were read through the resolver and left no host-disk entry. They were served
  from native retention, which was exact and bounded: a 24 MiB read peaked at no more than 13 MiB of Java heap, so a
  large local read is streamed rather than copied whole.
- For the largest video the restart-time cost was roughly 0.4 s to stream retained bytes plus 0.4 s to decode a first
  frame. This is local work, never a network transfer, and it is a plausible source of a transient spinner after a
  restart. This probe cannot say which spinner a user saw, because it does not render the tile.

## Preserved development runs

- The first run did not wait for the shipping own-copy publication. Its helper cancelled the state's scope as soon as
  the message was published, so no own send reported a host-cache hit after restart. That was a fixture teardown
  artifact rather than a product loss. The helper now waits for the projection and publication on every send, and the
  checker requires both the publication and the post-restart hit.

## Not qualified here

The conversation tiles themselves (first committed frame, one-tap playback, the automatic-download policy and
offline-without-policy cases are covered by unit and Robolectric tests, not by this report), media above 64 MiB
(the Android controller cannot send it), account switching and deletion on a device, physical devices, and
representative performance. Padded MP4s test size thresholds, not throughput. Private raw reports, logs and
checksums are retained outside the repository.

# Native attachment phases and failure recovery, 2026-10-03

Refs [#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). This report qualifies what the real
MDK runtime publishes to the same `nativeProgress` observer that the conversation file card renders. It changes no
shipping behavior and makes no performance claim.

## Method

`controller-phases` sends two generated files through the shipping `ConversationController.sendAttachments` path to a
generated peer over loopback: a paced 32 MiB file and an independent 1 MiB file. Nothing is seeded.

1. **Paced known-length body.** The server slows every body chunk, and the probe records each sample the
   production observer publishes. The body phase must report one monotonic byte count against the exact ciphertext
   length (33,554,448 bytes), then only forward phases, ending Ready with exact plaintext.
2. **Permanent miss and recovery.** The server answers 404 for the second file. The probe must observe a real
   failure phase, acknowledge cancellation first if that phase is a live deferred attempt, then admit exactly one
   deliberate Retry that ends Ready with exact bytes.
3. **Authoritative poll.** The native subscription is latest-wins, so a phase shorter than one wake-up can be
   skipped. A read-only poll of the same authoritative snapshot every 2 ms runs alongside it. It never requests,
   retries or cancels work.

The server ledger is authoritative: one upload each, one successful body for the paced file, only 404 attempts before
the single successful body for the other, and a committed terminal outcome for every request.
`phases_checker.py` fails closed on any missing or contradictory evidence and records `performance_qualified: false`.

## Source cohort

- Source: signed commit `6f01a5da5aad17cbd32e682f15f90da5f29f2f6c` on the head of #3021
  (`73673fff45b0d774484d95bfbfae2447c01f2959`), which rests on master `60988a94eb4c431fb35e36251d4aa60ac5204218`.
  The tree was clean and committed when each case was built.
- MDK pin `122bd90ffac60bb6311346e228d0f609a18521ee`, native bytes unchanged.
- Owned AVDs only: `wn_2779_fixture_api30` and `wn_2779_fixture_api36`, both arm64-v8a.
- Isolated `dev.ipf.whitenoise.android.medialatency` identities, installed in place.

| Case | App APK SHA-256 | Test APK SHA-256 |
| --- | --- | --- |
| Play | `97d467e6ec3deb75fe2fa8302c22c97ca8948238fafffee4bb3fcbfadd531f6e` | `8fb3ffabcdb5fbbbeb40ca637405214aae96ef134598d2d0fbcac5c6dac6511e` |
| Zapstore | `000b3f25c8b83369d27e357c62bf3dc8a4779dd4b4f6a2e2913142a6c2a96c5c` | `cd01152ea5e82cb060c7b0bf204e3a9ab0f2b3760411d54487fb36207d5e2390` |

The same APK pair ran on both API levels for each flavor.

## Results

All four cases pass the checker, the server ledger and the explicit API/ABI environment check.

| Case | Overall ms | Java peak MiB | Native peak MiB | Body samples | Uploads / GETs / HEADs |
| --- | ---: | ---: | ---: | ---: | --- |
| API 30 Play | 9830 | 77.7 | 204.8 | 27 | 2 / 4 / 0 |
| API 30 Zapstore | 10401 | 85.1 | 203.9 | 29 | 2 / 4 / 0 |
| API 36 Play | 9817 | 77.5 | 203.0 | 28 | 2 / 4 / 0 |
| API 36 Zapstore | 10030 | 77.5 | 203.3 | 29 | 2 / 4 / 0 |

Every case published the same transitions on the authoritative poll:

- Paced file: `NOT_REQUESTED, QUEUED, DOWNLOADING, VERIFYING_CIPHERTEXT, DECRYPTING, VERIFYING_PLAINTEXT, READY`.
- Permanent miss: `NOT_REQUESTED, QUEUED, DOWNLOADING, FAILED`, then after the deliberate Retry
  `QUEUED, DOWNLOADING, VERIFYING_CIPHERTEXT, DECRYPTING, VERIFYING_PLAINTEXT, READY`. The recovery of the smaller file
  can skip a phase on the poll as well, which is why the checker requires at least one post-body phase in the union of
  both sources rather than every phase in every scenario.

Each case made two 404 attempts and one successful body for the failing file (four GETs in total with the paced
file's one), and wrote 34,603,040 ciphertext bytes, equal to the 34,603,040 uploaded.

## What this shows

- The body phase carries monotonic bytes against the true ciphertext size, and ciphertext completion is not Ready:
  verification and decryption follow it, and only then does the file become Ready.
- The native runtime really emits `VERIFYING_CIPHERTEXT`, `DECRYPTING` and `VERIFYING_PLAINTEXT`, in order.
- A permanent miss surfaces as a real `FAILED`, which the file card renders as Retry, and one deliberate Retry
  recovers it without a second owner.
- **The subscription the UI uses can skip short phases, and which ones it catches varies.** In the committed cohort the
  production feed published `DOWNLOADING, VERIFYING_PLAINTEXT, READY` for the 32 MiB file in all four cases, and
  `DOWNLOADING, READY` for the 1 MiB recovery, so it never showed `VERIFYING_CIPHERTEXT` or `DECRYPTING` that time.
  The pre-rebase run on commit `8c1de4ecb` showed `DOWNLOADING, READY` for Play and `DOWNLOADING, DECRYPTING, READY` for
  Zapstore. Verification of a 32 MiB body takes tens of milliseconds on these emulators, so a card will not reliably
  show any one of these labels for an ordinary file and will show them for slower devices and larger bodies. This is
  the intended latest-wins behavior rather than a defect, but it means no test should assert that a particular
  transient label is always visible.

## Preserved development runs

These ran during development, before the committed cohort above, and are kept so the observation limits stay visible:

- Two 16 MiB runs on API 30 Play. One feed saw `DECRYPTING` and the other saw no post-body phase, and neither saw a
  verification phase. This is why the probe gained the authoritative poll and why the payload moved to the sender's
  32 MiB maximum.
- A first 32 MiB attempt used stale inputs: a `sed -i` that does nothing on macOS and a relative path that landed in the
  wrong directory. It exited with a script error, and its stale report was discarded rather than relabelled.
- An evidence run on API 30 from commit `8c1de4ecb`, before rebasing onto #3021, passed for both flavors. The cohort
  above supersedes it because conflict resolution changed the committed source.

## Not qualified here

Representative performance (these 32 MiB figures include fixture pacing and are not budgets), physical devices, the
rendered appearance of each phase (covered by `AttachmentProgressScreenshotTest` and `AttachmentProgressDeviceTest`),
Android scheduler stops, process death, and the video, image and voice tiles, which still show an indeterminate
spinner without byte progress or Cancel. Private raw reports, logs and checksums are retained outside the repository.

# Small text attachment forwards, phase by phase, 2026-10-04

Refs [#2556](https://github.com/marmot-protocol/whitenoise-android/issues/2556). This report records what the
shipping forward path does for a 1 KiB `text/plain` attachment on an owned emulator over a loopback relay and blob
server, phase by phase, before and after the forward learned to read MarmotKit's retained copy of the source. It is an
emulator measurement against a known-responsive loopback server. It does not reproduce the reported multi-minute stall,
does not qualify a physical device, and makes no claim about public infrastructure.

## What a forward does

For one message with one attachment and one destination, `startForwardMessages` runs these phases in order:

1. **Source reference.** The payload's reference is authoritative, or `findNativeAttachment` pages native history.
2. **Source materialization.** `materializeAttachmentPlaintextIsolated` reads the Android memory cache, the encrypted
   host-disk cache, then (after this change) MarmotKit's retained copy, and only then downloads the source with the
   legacy whole-body `downloadMedia`. The 120 s preparation timeout is the only forward-level bound.
3. **Destination upload.** `uploadMedia` with `send = false`, outside any lock and with no forward-level bound.
4. **Commit lock.** `withGroupCommitLock(account, destination)`, a FIFO mutex shared with every commit-producing call for
   that chat, including the composer's own media upload and publication, which hold it for their whole duration.
5. **Publication.** `sendMediaAttachments`, with no forward-level bound, then evidence classification on failure.
6. **Terminal state.** The owner retries transient destination failures with 1, 2 and 4 second delays.

`op=message_forward` in the local performance diagnostics now times each of these (see
[performance.md](../performance.md#send-and-attachment-diagnosis)). Before this change the path had no coverage.

## Method

`controller-forward` runs one instrumentation process against generated peers. The author sends the generated file
through the shipping `ConversationController.sendAttachments` path into the source chat, and the forwarder sends the same
bytes into its own source chat so that its encrypted host copy exists. The forwarder then sends the bytes **directly**
into the destination five times (the baseline), and forwards through `startForwardMessages` five times each with the
source:

- **uncached**: never opened by the forwarder, so no local layer holds it;
- **retained**: opened once through the unchanged resolver, so only MarmotKit's retained copy holds it (the resolver
  returns a lease, never bytes, so a received attachment never enters the forward's memory cache by viewing);
- **cached**: the forwarder's own send, whose encrypted host-disk copy the shipping publication wrote.

Each forward restarts a local diagnostics session, records the app's own phase lines, the time to the terminal state, the
author's genuine receipt and exact read of the forwarded copy, and brackets its requests with a ledger marker.
`forward_checker.py` requires every phase, exactly one completed message per forward with no retry or convergence, the
declared layer state before each forward, a source download only where no local layer holds the bytes, one receipt per
copy, one distinct destination message per send, and the issue's ceilings (15 s retained or cached, 30 s uncached). It
reports the slowest forward of each variant against the direct-send median without enforcing the issue's two-times
relation, because loopback fixed costs dominate both numbers.

## Source cohort

- Owned AVD `emulator-5554`, API 30 arm64-v8a, isolated `dev.ipf.whitenoise.android.medialatency` identities installed in
  place; Play distribution, `reference-api30-arm64` profile.
- MDK pin `122bd90f` (native bytes unchanged).
- Baseline: the fixture commit's working tree before the retained-source change. Post-change: the working tree with the
  change, lint and unit tests clean. Private raw reports, runner logs, APK digests and checksums are retained outside the
  repository.

## Baseline, before the retained-source read (two runs)

Every one of the 15 forwards per run completed, was received exactly by the author, and stayed far inside the ceilings.
Medians in milliseconds from the app's own phase lines, first run / second run:

| Variant | Total | Source lookup | Source download | Upload | Lock wait | Publish | Source GETs per forward |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| direct send (baseline) | 79 / 47 | | | | | | 0 |
| uncached | 126 / 104 | 1 / 0 | 13 / 7 | 24 / 15 | below 5 | 62 / 57 | 1 |
| retained | 227 / 83 | 1 / 0 | 15 / 7 | 41 / 14 | 5 once, else below 5 | 87 / 50 | **1** |
| cached | 103 / 106 | 9 / 9 | none | 17 / 24 | below 5 | 49 / 70 | 0 |

The ledger shows 22 uploads and **26** GETs per run: five source downloads for the uncached forwards, one acquisition
that retains the source, **five source downloads for the retained forwards**, and fifteen receipts. The forward
re-downloaded bytes the device already held in MarmotKit's retained copy on every retained forward. The slowest forward
of any variant was 381 ms (an uncached forward in the first run); the slowest direct send was 162 ms.

## After the retained-source read (three runs)

All three runs are `qualified` with no checker violation. Medians in milliseconds, run 1 / run 2 / run 3; the direct-send
medians were 22 / 24 / 33 ms:

| Variant | Total | Source lookup | Lookup served by | Source download | Upload | Lock wait | Publish | Source GETs per forward |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| uncached | 42 / 42 / 63 | 3 / 4 / 5 | none, miss | 3 / 3 / 5 | 7 / 7 / 9 | below 5 | 18 / 20 / 25 | 1 |
| retained | 42 / 43 / 62 | 3 / 4 / 5 | MarmotKit retained copy (`layer=mdk`) | none | 7 / 9 / 10 | below 5 | 17 / 22 / 22 | **0** |
| cached | 43 / 42 / 42 | 3 / 4 / 4 | host-disk cache (`layer=storage`) | none | 8 / 7 / 9 | below 5 | 16 / 20 / 21 | 0 |

The ledger shows 22 uploads and **21** GETs per run: five source downloads for the uncached forwards, the one
acquisition that retains the source, no download for any retained or cached forward, and fifteen receipts. Every
forwarded copy was received and read exactly by the author, and the destination holds exactly 20 distinct sent media
messages per run (5 direct, 15 forwarded), so no forward published twice. The slowest forward in the three runs was
83 ms (an uncached forward in run 2); the slowest of each variant was 1.8 to 3.5 times the run's direct-send median.
The emulator was warmer than during the baseline runs, which is why every number, direct sends included, is lower; the
baseline and post-change runs are therefore compared on request counts and phase shape, not on absolute time.

## What this shows

- On a responsive loopback path a small text forward finishes in a fraction of a second, and no phase comes near the
  reported minutes. The stall did not reproduce here; the measurement, not a fix, is what this environment can give.
- Publication is the largest phase of a forward on loopback, then the destination upload; the source phase is a few
  milliseconds whether it is a download or a local read. The commit-lock wait never reached the 5 ms reporting floor
  because nothing else wrote to the destination chat during the run. That lock is shared with the composer's own
  uploads, so a forward into a chat with a large send in flight waits for that send, and the new `commit_lock_acquired`
  line is the evidence to look for in a user report.
- A forward of an attachment the user had already opened fetched the bytes again over the network in the baseline. It
  now reads MarmotKit's retained copy first, so that forward no longer depends on the blob server at all.
- The forward is slower than a direct send by a fixed amount (a source read, a timeline read and an operation owner)
  which on loopback is two to five times the direct send. That relation cannot be judged here; it needs the physical
  matrix against known-responsive infrastructure the issue asks for.

## Not qualified here

A physical device, the issue's cached and uncached matrices against known-responsive public infrastructure, the
two-times relation to a direct send, a stalled source download, upload or publication (no phase has a forward-level
bound except the 120 s preparation timeout, and a publication deadline cannot be added safely without an idempotent
MDK publish), and the Zapstore distribution.

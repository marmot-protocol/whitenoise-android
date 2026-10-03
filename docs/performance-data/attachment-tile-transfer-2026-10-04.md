# Transfer progress, Cancel and Retry on media tiles, 2026-10-04

Refs [#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). The file card already shows real native
phases, byte counts and an acknowledged Cancel. This change gives the **image, video and voice tiles** the same
behaviour, and qualifies the image and video tiles through a genuine transfer on emulators. It is an emulator
measurement. It does not claim voice tiles on a device, a physical device, or representative performance.

## What a tile now does

| Tile state | What the reader sees | Action |
| --- | --- | --- |
| Queued, receiving, verifying | A ring (determinate only when the engine reports a trustworthy total) with real bytes, for example `3.0 MB of 8.0 MB` or `3.0 MB received`; a verifying phase names itself and never reads as a ready file | One tap on a 52 dp target cancels |
| Cancel sent, not yet acknowledged | An indeterminate ring reading `Cancelling download` | None until the engine answers |
| Cancel acknowledged | `Download cancelled. Tap to download again` | One tap downloads again |
| Failed, or Cancel unconfirmed | `Tap to retry` | One tap retries |
| Idle | Unchanged: the tile's own idle presentation, with no ring | |

The control sits in a fixed slot, so a tap on it never reaches the tile's own open handler. The single image tile also
draws the step and byte text for sighted readers. Album cells and video discs are too small for a caption and expose the
same text to TalkBack.

The transfer model (`TileTransfer`) reads the host state and the native observation without requesting, restarting or
cancelling anything. The engine feed is opened only while a tile is materializing, so idle tiles in a long conversation
hold no subscription. Own sends have no download to show.

## What the device test found

The first real-tile runs failed in three different ways. Two were product defects in this change's own first draft and
one was the harness. They are worth recording because host-only tests could not have found any of them.

1. **A Cancel never reached Cancelled.** After the reader's Cancel the engine stream disconnected, but the tile kept
   showing `352.0 KB of 3.0 MB` for the full 40 seconds the test waited. Native observation stops when the
   materialization intent returns to Idle, the last sample outlived its flow, and an override that treated a stale host
   Cancelled as Remote then believed that frozen sample. Samples are now dropped when observation stops and the override
   applies only while the tile is materializing.
2. **A failed video offered the wrong control and never recovered.** A video whose own materialization failed showed a
   Refresh icon with the voice-message string, and an accepted Retry opened the viewer without re-materializing the tile,
   so the tile stayed failed after the transfer had completed. A local failure now shows the shared Retry control
   (`Tap to retry`), and an accepted Retry clears the failure and re-materializes the tile.
3. **Harness only.** `Download again` is delivered only while the conversation is the foreground destination, which the
   shipping shell publishes when a conversation opens. The test did not publish it, so a retry was admitted but never
   reached the tile. The test now publishes the same foreground and active-conversation state.

## Method

`controller-tile-transfer` (`TileTransferDeviceTest`) sends three generated attachments from a sender runtime to a
receiver through the loopback relay and blob server, in a fresh process, with every image and video automatic-download
cell off so each download starts from the reader's tap. Each scenario hosts the **production** `MediaImageBubble` or
`MediaVideoBubble` and reads what a reader would see and hear:

- **Known length, cancel, download again.** A 3 MiB image. The server serves it, the tile shows real bytes below the
  total, Cancel is offered and tapped, the tile reads `Download cancelled…`, stays there for 1.5 seconds, then one tap
  restarts the transfer and it completes.
- **Permanent miss, retry.** A 9 MiB video. The server answers 404, the tile reads `Tap to retry`, the server recovers,
  one tap on Retry completes it.
- **Undeclared length.** A 3 MiB image with no `Content-Length`: bytes are shown with no total, then it completes.

`tile_transfer_checker` fails on a missing scenario, no real bytes, an unacknowledged Cancel, no restart or completion, a
failed tile that offers no Retry, or a length shown for an undeclared body. The server ledger stays the authority:
in the preserved runs the first GET of the known-length image ends with a client disconnect after roughly 0.7 MiB at
the Cancel, and the restart re-reads the whole body from offset 0 (a cancelled transfer does not keep its prefix),
serves all 3,145,744 bytes and completes.

## Results

Measured at `8784bfd31` (code identical to the commit that follows it, which only adds this report and the guide
entries), clean tree, both distributions:

| Environment | Known length, cancel, download again | Permanent miss, retry | Undeclared length |
| --- | --- | --- | --- |
| API 30 arm64, Play | bytes `352.0 KB of 3.0 MB`, Cancel acknowledged in **134 ms**, restarted and completed | `Tap to retry` shown, completed | `312.0 KB received`, no total, completed |
| API 36 arm64, Zapstore | bytes `352.0 KB of 3.0 MB`, Cancel acknowledged in **102 ms**, restarted and completed | `Tap to retry` shown, completed | `360.0 KB received`, no total, completed |

Both reports are `qualified` with no checker violations. Earlier failed and partial attempts, including the three
failures above, are preserved with checksums beside the passing runs.

## Host tests

- `TileTransferTest`: the model's states, the stale-Cancelled rule while materializing and not, a local failure
  offering Retry, the determinate range, the 48 dp target and the unknown-length text.
- `VoiceAttachmentControlsTest`: a downloading clip shows bytes and offers Cancel in place of Play, and a cancelled clip
  offers Download again.
- `AttachmentPresentationTest`: the reader's own Cancel returns accepted work to Idle so the tile offers Download again.
- `TileTransferScreenshotTest`: Roborazzi baselines in light, dark, and large-font RTL, for the image tile, the grid
  cell and the file card side by side across every state.

Locally the full suite needs `TZ=UTC`. Six unrelated screenshot tests (one poll, three emoji picker, two reaction
transition) still fail on this machine and fail identically on the unchanged base, so they are not attributable here.

## Not claimed

- **Voice tiles on a device.** They are covered by host tests and screenshots only. `voice-tile-device` is deferred.
- **A physical device.** None was used. `physical-device` is deferred.
- **Representative performance.** The fixture is a correctness check with a small loopback body. The engine feed
  coalesces to at least 250 ms ([mdk#2157](https://github.com/marmot-protocol/mdk/issues/2157)), so a transfer shorter
  than that can pass from Remote straight to complete without a visible ring.
- **#2045 is not complete.** Its other acceptance criteria are tracked on the issue. This change does not complete it.

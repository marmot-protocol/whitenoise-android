# Retained media tiles with automatic downloads off, 2026-10-03

Refs [#2909](https://github.com/marmot-protocol/whitenoise-android/issues/2909) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). This qualifies what the **real image and
video tiles** show for media that was genuinely sent and received and that only MDK still retains, and fixes the case
where they did not. It is an emulator measurement and does not replace physical-device acceptance.

## Method

The restored-runtime media fixture (`controller-media`) gains a third stage. After the prepare and restart-read
processes, a third process opens the same restored runtime for the sending and the receiving account with acquisition
unavailable and every image and video automatic-download cell turned off. It deletes the Android video playback cache
so that **only MDK's retention remains**, then renders the production `MediaImageBubble` and `MediaVideoBubble` for every
sent and received attachment (a single photo, three videos at 10 s, 9 MiB and 24 MiB, and a three-attachment album) in
one composition, one tile at a time. For each tile it samples every frame until the media is shown or ten seconds pass
and records:

- whether the tile shows its media, and how long that took;
- whether the **idle Download action** or Retry appeared at any sampled frame. The idle action is a node carrying the
  Download label that is not itself clickable in the unmerged semantics tree (its label sits on an icon inside a
  clickable surface). The loading spinner reuses the label on a clickable node while retained bytes are read locally, so
  it is not counted. Each frame is read once, so a tile changing between two reads cannot be read as two states;
- whether one tap hands the exact attachment to the viewer exactly once;
- a closed per-tile state trace (idle Download, spinner, shown, nothing) with the time of each change, which exposes
  any transition rather than only the end state.

`media_checker._check_tiles` requires all fourteen tiles and fails on any missing row, unshown tile, affordance or
unopened tap. The server ledger remains the authority: the whole run issues the seven original uploads and seven
original GETs and nothing after acquisition was made unavailable.

## Before

On the unfixed code, with only the fixture changes applied, **three received image tiles never show their image and
display the idle Download action**: the received single photo and album photos 0 and 1. MDK holds the verified bytes, but
the tile's cache-only path read only the host presentation caches and returned nothing for them, so the tile fell back to
offering a download that was neither needed nor possible. The sent photo, every video and album video, and the album's
third photo (a video) were unaffected. All tiles in the baseline run that did show took between 83 and 583 ms.

## The fix

- **A last local source.** A cache-only image render now reads host copies first and then MDK's own retention through
  the existing local open, never starting a transfer, and rejects a late result after an account change.
- **No premature Download.** A file the probes have not yet resolved no longer flashes its Download action. The action
  is offered only after the host and MDK probes have answered and the materialization intent has caught up with the
  policy they produced, so a retained file goes from the thumbhash placeholder to its image without a download prompt.
  A file that is genuinely absent still offers Download once the probes answer.

My own first draft also re-keyed an effect on a fresh object every composition and made Compose never idle, which 14
existing tests caught before any device run.

## After

From the same fixture, with the fix: **all fourteen tiles show their media, none displays the idle Download action at
any sampled frame, and every tile opens exactly once on one tap**, qualified on API 30 arm64 (Play), with the ledger
showing only the original seven uploads and seven GETs. The received image tiles go from the placeholder straight to
the image in about 100 to 350 ms, and videos show in about 115 to 525 ms (the 24 MiB video is the slowest). The first
cold pass after a fresh install is the slow one.

The same fixture, with the classification above, fails again when the fix is reverted: the three received image tiles
never show and keep the idle Download action. That check, and repeated passes of the fixed build, were run after the
fixture had flagged what turned out to be its own false positives, so how they were found matters:

- **A genuine frame.** A first version of the fix still flashed the Download action on album tiles for a frame,
  because the materialization intent follows policy one effect pass later. The action is now offered only when the
  probes have answered and the intent has absorbed the policy they produced, reported in the same effect pass as the
  grant.
- **A false positive in the probe.** After that, an intermittent first-run flag remained. Temporary composition
  logging showed no composition in which the action was offered while retained, so the probe had misread a transition.
  Reading each frame once and classifying the action from the unmerged tree removed it: five consecutive passes since
  then are clean, and the reverted build is still caught.

The 261 conversation-media unit tests pass, including a source test that pins the fallback order, that the read is a
local open that cannot start a transfer, and the account-change guard (it fails when the fallback is removed). Two
existing tests that expected the Download action immediately now wait for it.

## Source cohort

- Base: signed commit `829111244` (the head of #3028 at the time), plus the fixture commit and the fix. Development runs
  used the fix as an uncommitted overlay on the fixture commit; the overlay, the red and green reports and the APK
  digests are preserved. Hosted CI runs this stage on both distributions on the committed head.
- MDK pin `122bd90ffac60bb6311346e228d0f609a18521ee`, native bytes unchanged. Owned `wn_2779_fixture_api30` only.

## Limits

One emulator and one API level for the device stage; hosted CI adds API 34 on both distributions. Voice attachments are
not covered. It proves what the tiles display from retention and that one tap opens them; it does not prove first-frame
continuity across an Activity recreation, poster priming after restart, or the full-screen viewer. Those and the physical
acceptance #2909 asks for stay open.

# Read-aloud release verification

Use this checklist alongside **TTS-001**. It covers the physical evidence required
by [background playback](https://github.com/marmot-protocol/whitenoise-android/issues/1484)
and [position verification](https://github.com/marmot-protocol/whitenoise-android/issues/2089).
Unit tests and injected callbacks supplement these checks; they do not establish
that a real speech engine, headset or screen reader works.

## Record the actual candidate and engines

Record the full source SHA, APK SHA-256, distribution/package, device model,
Android version/build and both speech engines' package/version/voice/locale.
Use a trusted CI artifact for that source. Select an installed engine through
ordinary settings. Keep the user's accounts and data intact.

One engine must actually deliver usable `onRangeStart` callbacks; the other must
actually complete speech without them. A source comment or advertised feature
is insufficient. The opt-in instrumented case
`TtsRealEnginePositionAndroidTest` accepts `ttsEngine` and `ttsExpectedRanges`
arguments, uses the selected installed engine through the production adapter,
and verifies body word coordinates after sender narration. It leaves the default
engine unchanged. Run it once with each engine, recording actual test output.
Missing arguments skip the case and cannot count as physical proof.

## Exercise the complete reader journey with both engines

Use disposable messages containing several sentences, Markdown, links,
mentions, accented text, supplementary Unicode, replies, and long collapsed
messages. Include incoming and outgoing messages from different senders.

- [ ] Start read-aloud in the visible conversation. Words match the actual
  spoken body, without treating the sender prefix as message text. Sentence
  highlighting remains usable when word timing is estimated.
- [ ] Pause freezes progress; Resume retains the same session and location.
  Stop and natural completion clear the highlight and controls.
- [ ] Follow reveals the current sentence beneath the player. A direct drag
  retains manual ownership across sentence changes; Resume follow reveals the
  sentence without starting paused speech. Repeat after rotation and remount.
- [ ] Select a later sentence and use Speak aloud from here. The selected
  sentence starts, with correct sender handling and highlighted coordinates.
  Repeat while paused and with a row outside the retained queue.
- [ ] Edit/delete the source, switch conversations/accounts, replace the
  session/engine, pause, seek and stop while callbacks can still arrive. Old
  callbacks never paint a different source or move the new cursor.
- [ ] Enable the installed TalkBack service through Settings. Inspect and listen
  to the transport names, progress announcements and position semantics.
  Announcements stay polite and useful without disrupting text navigation.
  Record the TalkBack version and restore its previous setting afterward.

## Background playback and recovery

- [ ] Background White Noise, open another app, lock/unlock and recreate the
  Activity during speech. The same queue continues; returning reconnects to its
  current location without replaying completed messages.
- [ ] Deliver several eligible messages in one native update while speaking,
  then while paused. Every new message joins oldest first exactly once.
  Repeated snapshots do not add duplicates. Paused arrivals wait for Resume.
  Deliver an arrival during a Previous/Next history-edge load; it waits for
  settlement and is revalidated from the native snapshot without being skipped.
- [ ] Pause/Resume/Stop from the app, notification and lock screen agree with
  one playback owner. Exercise an actual compatible headset and record it;
  simulated media keys alone do not prove headset support.
- [ ] Trigger transient and permanent audio-focus loss with another audio app.
  Both retain a resumable queue; Resume reacquires focus. Media mixing follows
  the chosen preference without taking over the other app's playback.
- [ ] Interrupt the speech engine during a multi-sentence queue. Playback pauses
  at the earliest unfinished sentence, retains later messages and releases
  focus. Explicit Resume retries; repeated failure stays paused rather than
  spinning or silently skipping ahead.
- [ ] Dismiss the notification or press Stop. Speech, focus, notification and
  service/session resources end; late callbacks do not resurrect them.
- [ ] Confirm notification and lock-screen surfaces contain only generic
  playback text, with no sender, chat or decrypted message preview.
- [ ] Remove the task from recents and exercise the configured app-lock boundary.
  Both follow the existing privacy policy and explicitly stop speech. Ordinary
  screen lock alone is not task dismissal.
- [ ] Recreate the OS process. This implementation retains no persisted speech
  queue or recovery cache: the non-sticky service starts idle. Playback never
  reconstructs decrypted text or resumes automatically after process death.
- [ ] Lose the native timeline feed or exceed its retention budget during pause.
  Speech stops and discards the captured queue; Resume cannot submit stale text.
  A fresh playback start reloads authoritative content.

The process-owned auto-read subscription follows MDK's ordered window and pages
through a missing tail instead of jumping past unseen messages. A native gap or
subscription failure ends the session so disconnected captured text cannot be
resumed before revalidation. A fresh playback start is required. Native edits/deletions invalidate captured
speech even while the conversation screen is absent.

Live continuation retains at most the native window's 200 messages and 1,048,576
UTF-16 units across each message's authored/spoken text, using the larger length.
Exceeding either cumulative budget stops and discards the session instead of
dropping unfinished messages or growing an unattended queue indefinitely.

## Results and closure

Record each row as PASS, FAIL or NOT RUN with the candidate/engine identity and
supporting output. Repeat on the supported Android matrix identified by the
release guide. File concrete failures under their owning issue; leave missing
hardware, engine, OS or TalkBack evidence visible. Do not close the verification
issue or its tracker from unit/emulator success or a partly completed checklist.

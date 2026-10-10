# Read-aloud qualification

Read-aloud uses one Android playback controller across the conversation,
attachment reader and system media transport. Session replacement must revoke
old controls and callbacks while retaining the replacement queue, passage and
audio-focus owner.

This qualification covers [text-to-speech #1478](https://github.com/marmot-protocol/whitenoise-android/issues/1478)
and its [position-aware playback tracker #1776](https://github.com/marmot-protocol/whitenoise-android/issues/1776).
Every implementation child of the position-aware tracker is closed. The parent
has only that tracker remaining open. These counts identify the remaining
qualification boundary; they do not establish a passing user journey.

## Session handover

`TtsPlaybackMediaSessionCallback` captures the controller and session that
created it. Pause, Play, sentence navigation and Stop require that session to
remain active. A callback retained after replacement cannot control a new
queue. A valid service start refreshes the callback for the current session.

Service teardown stops only the playback session it observed. Re-resolving the
application host during teardown could otherwise stop another account's
controller or a replacement queue awaiting its own foreground-service start.
Changing the observed controller cancels the previous subscription before
observing its replacement.

The service owns no duplicate queue or message cache. Engine speech,
conversation highlighting and system transport retain their existing owners.
No MarmotKit pin, generated binding or shared protocol behavior changes.

## Required evidence

- `TtsPlaybackForegroundServiceTest` checks original-host teardown, a delayed
  teardown after replacement, renewed service ownership, stale MediaSession
  callbacks, current controls, sentence navigation, retained pause and generic
  notification metadata.
- `TtsHighlightPlacementAndroidTest` checks real Compose glyph placement for
  native ranges, wrapped text, Markdown and RTL. Additional cases check the
  range-silent estimated-word lane, pause/resume and revoked late callbacks.
  The class is selected by the PR device-smoke annotation. The additional cases
  are also required by the full instrumented-suite result checker.
- `TtsQuickTransportViewportAndroidTest`, the existing follow-policy tests and
  reader tests cover viewport ownership, cancellation and attachment parity.
- `TtsRealEnginePositionAndroidTest` is an explicit opt-in installed-engine
  check. Record the selected engine and observed native-range capability;
  deterministic callback injection is separate evidence.
- Permanent manual case `TTS-001` covers actual system controls, session
  replacement, engine fallback and visible playback. `TTS-002` and `TTS-003`
  retain the engine-selection and trust-warning contracts.

## Completion boundary

Keep both trackers open until the qualified candidate passes the full relevant
CI suite and physical-device lifecycle, audio-focus, selection, accessibility,
highlighting, follow and engine-fallback checks. Record source revision and
engine identity with device evidence. A successful emulator run, fake callback
or installation smoke does not prove an installed engine's capability or the
complete visible user journey. A draft's closing references describe intended
completion on merge and do not assert that pending checks have passed.

Notification intents still use the existing application transport route. This
change binds MediaSession callbacks and teardown; it does not introduce a new
notification scheme or change the privacy-safe metadata.

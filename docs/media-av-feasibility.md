# Audio/video containment feasibility

This study concerns attachment parsing in [#3374](https://github.com/marmot-protocol/whitenoise-android/issues/3374).
The selected-descriptor isolated prototype is available in debug builds. Supported
permissionless platform A/V parsing remains unproven. Production automatic probes
and playback retain the app-process exposure described below.

## Process and capability inventory

| Path | Parser and process | Input and output capabilities | Residual exposure |
| --- | --- | --- | --- |
| Automatic video poster and duration, including draft and received files | `PlatformMediaTransport` calls `MediaProbeDecoder` and `MediaMetadataRetriever` from the app process; Android performs further work in media services | Bounded selected file descriptor; scalar duration/dimensions and copied bitmap | JNI/Binder client has app permissions and private-data access |
| Automatic voice waveform | `PlatformMediaTransport` calls `AudioWaveformExtractor`; app-process `MediaExtractor` and `MediaCodec` clients | Selected descriptor; exactly 64 finite normalized bars | Client/native setup retains app authority; timed-out native calls keep the serialized slot |
| Received video viewer and looping playback | `MediaVideo` creates Media3 `ExoPlayer` in the app | `LocalVideoSource` admits file/content and explicitly builds a progressive source; decoded output goes to the player surface | Media3 container extraction runs with app authority; repeat mode adds no process boundary |
| Pending/draft video | `DraftVideoPreview` creates its own Media3 player in the app | Materialized local URI, progressive source and player surface | Same app-process extraction exposure as received video |
| Voice playback | `VoicePlaybackController` constructs app-process `MediaPlayer` | Selected local descriptor, audio output and existing audio-focus owner | Platform playback performs remote work, while the client retains app authority |
| Attachment transcription PCM | `AttachmentPcmDecoder` constructs app-process extractor/codec clients | Selected local descriptor and bounded PCM consumption | An additional explicit parsing path; automatic-waveform evidence does not qualify it |
| Debug automatic-probe prototype | `MediaAvCapabilityService` under an isolated UID | Read-only selected descriptor, bounded parent-created shared pixel memory and per-request reply Binder; synthetic canary path for the access-denial check | Android media-service availability and codec support are unqualified; no account/runtime objects or file-opening callback |

Notification tone preview uses `MediaPlayer` for selected local tones. It does not
consume an attachment, and its source/lifetime owner remains separate from this
prototype. MDK retains authenticated acquisition and account state; this study
found no missing MDK contract needed for the descriptor experiment.

The app validates descriptor offset/length before dispatch. Provider subranges
must be copied into their own unlinked selected inode before a production parser
receives them; possession of a descriptor can expose its entire inode. Debug
fixtures use complete synthetic files. The prototype sends neither network URLs
nor account objects. Its canary string names a synthetic test-owned file and is
excluded from release requests.

## Platform options and decision

Android's [isolated service contract](https://developer.android.com/guide/topics/manifest/service-element#isolated)
provides a distinct process with no app permissions. The existing
`bindIsolatedService` prototype uses that contract without hidden APIs. A new
instance name permits a separate service instance; the host still admits one
worker and requires exact Binder death before replacement. A process name alone
retains the app UID and its private-data access.

| Option | App-data/network boundary | Feasibility and decision |
| --- | --- | --- |
| Existing isolated service with platform media clients | Permissionless UID; selected descriptor and reply capabilities | Retain as the measured prototype. API 30 service lookup blocks the retriever; successful parsing is unqualified on the tested corpus |
| Separate same-UID `:media` service | Retains app data and permissions | Can contain some crashes and provide a killable process. It cannot satisfy the requested least-privilege boundary |
| App-side media-service proxy or general opener | Delegates app capabilities into the worker | Excluded from this prototype: it changes the measured authority and needs a separately bounded protocol/security review |
| Bundled software demuxer/decoder in an isolated service | Can retain selected-input authority | Requires a reviewed maintained codec dependency, supported-format policy, resource limits and compatibility evidence. No new dependency is selected in this study |
| Separate installed permissionless package | Separate app sandbox, installation and IPC contract | Changes packaging/distribution and upgrade behavior; outside the unchanged playback-UX constraint |

Explicit playback decision: preserve the current local-source players and audio
focus/lifecycle owners during feasibility work. A same-UID service will not be
presented as full containment. A future isolated player must receive selected
input and a render surface through a fixed protocol, return bounded scalar
state, and revoke stale account/session commands. It must preserve seeking,
looping, pending/received transitions and background/resume before adoption.
Passing platform codec tests alone cannot qualify Media3 container extraction.

## Retained API and format results

These are historical observations on their recorded source, distinct from
current-source qualification. An instrumentation test can pass a negative
characterization contract while parsing remains unsupported.

| Runtime and source | Synthetic corpus | Observation | Interpretation |
| --- | --- | --- | --- |
| API 30, `089739188cd1` | Silent-black MP4, PCM WAV, truncated MP4 and invalid ranges | [Run 39991836881](https://github.com/marmot-protocol/whitenoise-android/actions/runs/39991836881): retriever waits for unavailable `media.player` until the six-second watchdog kills it; extractor source returns `IOException` after service lookup | Failed characterization deadlines; no successful isolated A/V support established |
| API 34, `e16f701316de` | Same fixed MP4/WAV corpus and invalid ranges | [Run 40096013537](https://github.com/marmot-protocol/whitenoise-android/actions/runs/40096013537): seven soft refusals, waveform `IOException`/`no_result`, two parent range refusals; A/V JUnit passed and the workflow failed an unrelated network fixture | Complete negative characterization; zero successful poster, duration or waveform output |
| API 36, current candidate | Required unchanged MP4/WAV corpus | No retained A/V capture found | Pending; API 34 evidence supplies no API 36 qualification |
| Other containers, codecs and ordinary messenger sources | Not covered by these fixtures | No format-specific result | Pending; a safe refusal is distinct from successful format support |

## Failure, output and lifetime contract

The host validates the kernel READY UID/PID and binds each reply to its exact
endpoint, token and request. Replies cannot carry arbitrary descriptors. Successful
pixels are copied into a bounded app-owned bitmap after exact worker death;
scalars and waveform bars pass the existing primitive validation. A worker's
existing writable mapping remains untrusted until retirement. The host owns the
copy and treats its pixel values as untrusted content.

Startup without a confirmed endpoint remains held. Cancellation revokes the
callback and asks that exact endpoint to retire. Unbind, disconnect and elapsed
time do not prove death. A six-second independent hard watchdog terminates probe
work; waveform uses its existing longer ceiling. A blocked callback cannot delay
the independent kill. A missing READY, death witness or successful handle cleanup
stops admission. Late output is discarded. Production account invalidation stays
with the existing media/account owners; the debug experiment has no account.

The current campaign includes malformed/truncated input and callback protocol
fixtures, but supplies no complete production account-change or background/resume
qualification. App-process native work remains non-killable without terminating
the app. Endpoint death alone also supplies no descendant-process lifetime proof.

## Remaining qualification and implementation slices

1. Run the changed capture/report contract on API 30 and API 36 through the
   focused hosted containment campaign. Preserve exact source/APK, JUnit and raw
   reports. Review every refused stage and avoid interpreting codec access from
   an earlier extractor failure.
2. Measure startup and retirement separately, plus worker/caller RSS, descriptors
   and threads across repeated failures and healthy recovery on the same corpus.
   The historical reports do not establish a production memory or throughput
   budget. Increasing deadlines cannot supply a denied service capability.
3. Select a bounded software parsing alternative only after its maintenance,
   format coverage and resource costs are reviewed. Prototype automatic outputs
   first, with cancellation and noncooperative-call retirement.
4. Qualify explicit surface/audio playback separately, including account changes,
   seeking, focus, loop state and background/resume. Keep current exposure visible
   until that implementation is reviewed and measured.

The current change completes report validation and records the feasibility
boundary. The unsupported platform paths and remaining acceptance evidence keep
#3374 open.

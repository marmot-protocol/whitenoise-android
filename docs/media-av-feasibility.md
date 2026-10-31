# Audio/video containment feasibility

This document retains the original attachment-parsing study for
[#3374](https://github.com/marmot-protocol/whitenoise-android/issues/3374).
The inventory and explicit-playback decision below describe the pre-migration
baseline. The current implementation is documented in
[Multimedia containment](media-containment.md): automatic output, local attachment
playback, voice playback and transcription now use isolated software decoders.
Media3 controls operate over raw output; historical app-process parser entries
below do not describe the new local-attachment path. Current-source build,
API 30/current-API, corpus, visual and performance qualification remain pending.
Remote Nostr network playback retains its separate platform path.

## Historical process and capability inventory

This table records the automatic-only implementation stage: software automatic
outputs were present, while playback and transcription still used their prior
app-process paths. It is retained as the migration baseline.

| Path | Parser and process | Input and output capabilities | Residual exposure |
| --- | --- | --- | --- |
| Automatic video poster and duration, including draft and received files | `IsolatedMediaTransport` calls `MediaAvDecodeService`; FFmpeg/dav1d demux and decode in a permissionless process | Bounded selected file descriptor; scalar duration/dimensions and copied bitmap | Fixed raw-pixel/scalar reply boundary; current-source positive output and capability tests pending |
| Automatic voice waveform | `MediaAvDecodeService` decodes PCM in the isolated UID and uses the shared 64-bar signal accumulator | Selected descriptor; exactly 64 finite normalized bars | Native decode is killable without terminating the app; replacement still requires exact death |
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
| Bundled software demuxer/decoder in an isolated service | Selected-input authority with raw outputs | Selected for production: pinned FFmpeg 9.0.2 plus dav1d 1.5.4, disabled network/protocols and secondary opens, bounded output and explicit resource refusals. Automatic lane was integrated at this decision; the current playback implementation and all current-source platform qualification are described above |
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

The historical debug experiment validates the kernel READY UID/PID and binds
each reply to its exact endpoint, token and request. Replies cannot carry arbitrary descriptors. Successful
pixels are copied into a bounded app-owned bitmap after exact worker death;
scalars and waveform bars pass the existing primitive validation. A worker's
existing writable mapping remains untrusted until retirement. The host owns the
copy and treats its pixel values as untrusted content.

In that experiment, startup without a confirmed endpoint remains held. Cancellation revokes the
callback and asks that exact endpoint to retire. Unbind, disconnect and elapsed
time do not prove death. A six-second independent hard watchdog terminates probe
work; waveform uses its existing longer ceiling. A blocked callback cannot delay
the independent kill. A missing READY, death witness or successful handle cleanup
stops admission. Late output is discarded. Production account invalidation stays
with the existing media/account owners; the debug experiment has no account.

The historical campaign includes malformed/truncated input and callback protocol
fixtures, but supplies no complete production account-change or background/resume
qualification. Its old app-process parsing paths required terminating the app to
stop native work. Endpoint death alone supplies no descendant-process lifetime proof.

The current production automatic route requires a synchronous kernel UID/PID
READY callback before input dispatch. The parent calibrates the PID as present,
checks the final callback against the same UID/PID and per-request capability,
and requires a later ESRCH observation and successful unbind before copying
output. Replacement also waits for parent-resource cleanup. Binder endpoint
death alone leaves admission held. A mandatory
TSYNC seccomp filter denies process creation before native parsing. These source
contracts require current-source kernel and device qualification.

## Historical implementation sequence and remaining qualification

1. Run the changed capture/report contract on API 30 and API 36 through the
   focused hosted containment campaign. Preserve exact source/APK, JUnit and raw
   reports. Review every refused stage and avoid interpreting codec access from
   an earlier extractor failure.
2. Measure startup and retirement separately, plus worker/caller RSS, descriptors
   and threads across repeated failures and healthy recovery on the same corpus.
   The historical reports do not establish a production memory or throughput
   budget. Increasing deadlines cannot supply a denied service capability.
3. The software parsing alternative is implemented. Qualify its maintenance,
   format coverage and resource costs, including cancellation and
   noncooperative-call retirement.
4. Qualify explicit surface/audio playback separately, including account changes,
   seeking, focus, loop state and background/resume. Keep current exposure visible
   until the implemented path is reviewed and measured.

The implementation now includes the native source build, isolated automatic
poster/duration/waveform route, raw-output playback, attachment transcription and
report validation. Positive current-source build, platform, compatibility and
performance evidence remain required; #3374 stays open.

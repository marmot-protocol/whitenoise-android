# Attachment security plan

PR #3382 began with test-only still-image worker characterization. This source
candidate adds a retained still-worker lifetime owner, per-input retirement and
an exact Binder-death barrier before the existing parent-owned pixel copy.
Upload preparation, download policy, animation and platform audio/video remain
unchanged. All five issues below remain open. API 30/API 36 qualification,
matched performance comparison and production-payload inspection remain pending.
The earlier API 30 diagnostic run failed its full-corpus assertion; it supplies
failure evidence only. Default adoption awaits successful platform evidence,
measured regression budgets and review.

The baseline procedure and its measurement limits are recorded in
[Still-image worker baseline](media-worker-baseline.md). Its 100-request corpus
contains one first-request observation, while a cold-start distribution requires
repeated first-request samples. Direct transport timings also leave visible chat,
scroll and viewer latency unmeasured.

## Boundaries and evidence

Selected files are hostile input even when their sender is trusted. Encryption
preserves embedded metadata for recipients after decryption. Each proposed
change must keep account state outside restricted workers and preserve existing
descriptor, output, admission and storage bounds documented in
[Media containment](media-containment.md).

| Issue | Owning source boundary | Confirmed gap and evidence limit | Next stage and acceptance | Dependency |
| --- | --- | --- | --- | --- |
| [#3373](https://github.com/marmot-protocol/whitenoise-android/issues/3373), still-worker lifetime | `IsolatedMediaDecoder`, `IsolatedMediaTransport` and `MediaDecodeService` | The earlier transport reused one worker across distinct inputs. This candidate retains the lifetime owner and waits for exact Binder-host death per input. Persistence and sandbox escape remain threat scenarios with explicit descendant limits. | Review and qualify the implemented lifetime/reset fence, one-input binding generations, reply revocation, exact Binder-death barrier, bounded parent-private pixel copy and failure holds. | Matched baseline measurements, reviewed death/reset semantics and supported-platform tests. Complete descendant termination remains a separate proof obligation. |
| [#3374](https://github.com/marmot-protocol/whitenoise-android/issues/3374), audio/video containment | `PlatformMediaTransport`, `BoundedMediaExecutor`, waveform extraction and Media3 playback | Automatic media work has app-process clients; Media3 extraction runs in the player process. Existing platform services provide some isolation. Exploitability remains unproven. | Publish an API/format/process capability matrix and a minimal automatic-probe prototype on API 30 and API 36. Record denied services, selected-FD/output behavior, cancellation and hung-call limits; decide playback separately. | Actual platform feasibility. A same-UID service can contain some crashes while retaining app-private-data and permission access. |
| [#3375](https://github.com/marmot-protocol/whitenoise-android/issues/3375), trusted automatic downloads | MDK acquisition policy and Android `MediaAutoDownloadMatrix`, `AppState`, Data usage and identity selection | Existing controls cover network/type permission. Trusted-sender policy requires shared enforcement across background/retry acquisition. | Adopt the published shared contract, then add account-scoped explicit pubkey selection, restricted-empty behavior, truthful save/error states and a separate Download once action. Prove enforcement before remote reads across every attachment type. | [MDK #2512](https://github.com/marmot-protocol/mdk/issues/2512), including storage, admission, retry/revocation and per-item manual permission. An effective toggle requires publication and adoption of the MarmotKit contract. |
| [#3376](https://github.com/marmot-protocol/whitenoise-android/issues/3376), outgoing video privacy | `MediaPipeline.readVideoForUpload`, `ConversationMediaSender` staging and accepted draft/share payloads | This candidate scrubs ISO BMFF and Matroska container metadata in place, at the same size, before acceptance on every local selection path. Visual picks reported as video, and Files picks whose content is a video, are refused when they cannot be cleaned; other Files picks stay byte-for-byte. Which private fields occur in production camera payloads remains unmeasured. | Follow the [video metadata policy](video-metadata-policy.md). Real-camera corpus inspection of the exact final bytes passed to MDK, and device playback of scrubbed output on API 30 and a current API, remain to be recorded. | The policy's field table, the generated fixtures and the device test. The in-app parser runs in the app process; app-process parser exposure remains tracked by #3374. |
| [#3377](https://github.com/marmot-protocol/whitenoise-android/issues/3377), document privacy | Document selection/preparation and byte-preserving generic upload | Generic documents retain original bytes. Removal semantics and capability preservation for PDFs and office formats are unqualified. | Approve a true-format policy and bounded processing design before selecting a library or enabling preparation. Validate PDF Info, XMP and historical revisions, and preserve promised document capabilities. | Exact processor/version/security review, resource boundary and real document corpus. Processor selection remains pending. |

Source entry points:
[image facade](../app/src/main/java/dev/ipf/whitenoise/android/media/sandbox/IsolatedMediaDecoder.kt),
[still transport](../app/src/main/java/dev/ipf/whitenoise/android/media/sandbox/IsolatedMediaTransport.kt),
[worker service](../app/src/main/java/dev/ipf/whitenoise/android/media/sandbox/MediaDecodeService.kt),
[platform transport](../app/src/main/java/dev/ipf/whitenoise/android/media/sandbox/PlatformMediaTransport.kt),
[media preparation](../app/src/main/java/dev/ipf/whitenoise/android/media/MediaPipeline.kt),
[attachment sender](../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationMediaSender.kt),
[draft preparation](../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationMediaDraft.kt)
and [incoming shares](../app/src/main/java/dev/ipf/whitenoise/android/share/InboundShareImportState.kt).
The remaining owners are
[waveforms](../app/src/main/java/dev/ipf/whitenoise/android/audio/AudioWaveformExtractor.kt),
[video playback](../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt),
[download permissions](../app/src/main/java/dev/ipf/whitenoise/android/state/MediaAutoDownloadMatrix.kt)
and [Data usage](../app/src/main/java/dev/ipf/whitenoise/android/ui/settings/DataUsageScreen.kt).

## Still-worker retirement and output handling

This source candidate uses the existing serialized transport and binding monitor.
It reserves a generation before binding, registers death on the framework-delivered
remote Binder before sending descriptors and dispatches one request to that
generation. Each terminal result revokes its reply capability. A successful
response remains provisional while retirement is unresolved. The
[still-image lifetime candidate](media-worker-lifetime.md) records the implemented
owner, deadline and test boundaries; platform qualification remains pending.

The candidate keeps the generation and death recipient through cancellation,
account reset, clear and reinstall. Its process-owned lifetime owner survives
facade replacement, preserving the held generation across those operations. Late replies and late connections have generation-specific cleanup
paths and receive no new source. Waiting must occur outside the binding monitor,
and bind, reply and retirement share a bounded monotonic operation deadline.

RETIRE, unbind, disconnect and service-destruction callbacks cannot certify
process death. The [Binder death contract](https://developer.android.com/reference/android/os/IBinder.DeathRecipient)
identifies the process hosting that Binder. A worker that ignores retirement
leaves subsequent admission held; cancellation or account reset cannot convert
uncertainty into permission. Startup without a delivered Binder also lacks this
death capability. Preserve the unresolved generation and handle a late Binder
without dispatching an input. The host's process-kill permissions do not provide
an arbitrary isolated-worker termination guarantee.

Binder-host death has a narrower scope than process-group retirement. The
inspected API 30
[SELinux](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-11.0.0_r1/public/domain.te)
and [seccomp](https://android.googlesource.com/platform/bionic/+/refs/tags/android-11.0.0_r1/libc/SECCOMP_WHITELIST_COMMON.TXT)
policies, alongside their API 36
[SELinux](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-16.0.0_r1/private/domain.te)
and [seccomp](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r1/libc/SECCOMP_ALLOWLIST_COMMON.TXT)
counterparts, permit process creation relevant to inherited writable mappings.
Framework process-group cleanup provides defense in depth, but the Binder
callback supplies no group-empty receipt. These observations establish a limit
on the proposed proof; they do not establish a UID-reuse vulnerability.

`IsolatedMediaTransport.copyPixels` already validates dimensions and byte extent
through `MediaDecodePolicy.pixelBytes`, copies the bounded shared pixels into a
new parent-owned `Bitmap`, then unmaps the shared buffer. Reuse that validated
private copy. The candidate places exact Binder-host death before
this existing copy; an extra `ByteArray` or second pixel copy would require a
separate identified defect.

Capture output metadata once and keep the existing checked extent and bitmap-size
validation. Downstream code must use the parent-owned bitmap and treat every
pixel byte as hostile. Another surviving mapping could change pixels during
copying, so the copy provides stable bounded downstream bytes without pixel
authenticity or an atomic image snapshot. Bounds-safe raw-pixel interpretation
must hold for every possible byte sequence. Do not feed worker-supplied encoded
output into a privileged decoder.

[SharedMemory protection](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/os/SharedMemory.java)
leaves existing mappings unchanged. Stronger global immutability would require
a separate compatible transport proof, such as successful kernel write sealing
that refuses surviving writable mappings. That is an optional later design,
with API/kernel qualification and explicit refusal where unsupported. Animation
sessions keep their separate lifetime policy.

## Audio/video feasibility

List every automatic poster, duration and waveform path and every received,
pending, looping and voice-playback path. For each API and supported format,
record the parsing process, selected descriptors, reachable services, app-data
access, network access and returned pixels/scalars/PCM or surfaces. Preserve
existing local progressive-source restrictions, audio focus and playback controls.

The prototype must exercise real isolated-service restrictions on API 30 and
API 36. Service access denied to an isolated UID is a measured compatibility
result. Any retained app-process route needs its own documented exposure and
bounds. A same-UID process must be labelled crash containment, with its retained
data permissions stated. A coroutine timeout cannot terminate a hung native
call; the existing bounded executor slot stays occupied until that work ends.
No replacement thread or worker may evade that resource hold.

## Video preparation and exact-byte ownership

Begin with an explicit supported table, for example a separately validated MP4
AVC video track with optional AAC audio. Track count, codec configuration,
orientation, sample ordering, presentation timing, audio synchronization and
duration must survive preparation. HEVC, HDR, Dolby Vision, subtitles, multiple
audio tracks, edit lists, encryption and other containers require their own
preservation fixtures before support. Refuse a file whose required tracks or
playback fields cannot be preserved.

[MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)
and [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)
provide a candidate remux path, with a reviewed codec-format allowlist and
explicit flag mapping. Their API surface does not establish a complete metadata
removal guarantee. Inspect the resulting container for the documented location,
date, device, author/comment and other private-field policy. Required codec data,
visible/audio content, filenames and covert values remain distinct categories.

Use the existing staging owners and resource admission. Reserve input and output
disk, cap host buffers and output growth, check owner/cancellation before
acceptance, and clean task-owned failed or late output exactly once. Native
internal allocations need separate measurement. Validate a completed new output
before it becomes a `PendingAttachment`; preserve the local source unchanged.

Photo Picker, Android shares and Files routing must converge on this preparation
before acceptance. Draft restore, retries and both in-memory/file-backed sends
must retain an exact-byte preparation policy binding or reprepare/refuse old
unqualified video drafts. Inspect the payload at the MDK boundary, including
file-backed bytes, and record its exact content digest. Keep generic
documents byte-preserving and forwarded authenticated references unchanged.
Preparation failure must never select original video bytes silently.

## Trusted downloads and document design gates

Trusted download policy belongs in the shared acquisition owner. Android can
present explicit stable-pubkey selection, account identity and a separate manual
action once MDK #2512's published contract supplies authoritative persistence,
typed blocking and revocation. Suggestions from DMs or follows require explicit
selection. Test network/type controls together with the audience setting,
background history retries and account changes. Avatars, external URL images
and Nostr cards need separate scope audits before the UI promises coverage.

Document preparation remains a design gate. Detect true format within bounded
input reads; filename extensions and declared MIME types cannot select a parser
by themselves. Define PDF and each office format independently. PDF review must
cover document-information properties, XMP and identifying values in incremental
revisions, with a rewrite policy that does not leave historical values behind.
Preserve searchable text, layout, forms and values, accessibility tags, bookmarks,
links, annotations and intended embedded content under the supported policy.
Detect signatures before rewriting because modification can invalidate them.
Encrypted, signed or unsupported documents need explicit supported behavior and
original/cancel choices. Removing properties cannot promise removal of visible
identity, comments, embedded files or identifying filenames.

The [PDFBox security policy](https://pdfbox.apache.org/security.html) excludes
CPU, memory, recursion and processing-time denial of service from its security
scope and requires application resource controls and sandboxing. A library call
inside a coroutine timeout cannot establish hard parser bounds. Select no
processor until its exact Android-compatible version, maintenance/security
coverage, cancellation/process boundary, file expansion and temporary-storage
limits have been reviewed against a real corpus.

For a supported privacy-prepared document, Keep metadata starts off. Enabling it
is an explicit per-attachment original-byte choice with a disclosure. Preserve
that choice through drafts/retries and bind it to the exact payload. A batch
choice applies to selected attachments; it must not become a hidden future-send
default. Unsupported preparation offers an explicit original/cancel decision
and never falls back automatically.

## Independent validation matrix

| Boundary | Required independent evidence |
| --- | --- |
| Still lifetime | Controlled unit callbacks prove dispatch, reply revocation and copy ordering. Real API 30/API 36 Binder tests cover delayed death, ignored RETIRE, startup without Binder, cancellation, reset/clear/install, stale callbacks and healthy replacement. A retained descendant mapping tests the documented pixel/proof limit. |
| Output and resources | Malformed dimensions/stride/extent and arbitrary raw pixels remain bounded. Compare the same fixed corpus/device/configuration before and after, including transient snapshot memory, FD/process trends, first-request samples and visible scrolling/viewer/notification behavior. Set budgets after measurements. |
| A/V prototype | Real selected-FD capability tests record parsing process and denied access, malformed/truncated inputs, native hangs, cancellation, account changes and healthy-source recovery. Playback tests cover seeking, focus and background/resume. |
| Trusted acquisition | Shared integration evidence proves zero automatic reads for denied senders, restricted-empty state, same-name/different-pubkey selection, per-item manual permission, account isolation, failed writes and background/in-flight revocation. UI assertions supplement this enforcement proof. |
| Video bytes | Independent container inspection and playback compare removed fields, retained samples/tracks, timing, orientation and color/HDR promises on the exact MDK-bound output. Cover every selection/share/draft/retry and size route, storage pressure, cancellation and stale owners. |
| Documents | Real Info/XMP/incremental-revision, searchable/tagged/form/annotation/signature/encryption fixtures verify the declared transformation or explicit decision state. Keep-metadata payloads match source bytes. Exercise resource exhaustion and preservation of the original local file. |

Run Android compilation and automated test campaigns, including hosted
instrumentation, through exact-head GitHub CI. Perform applicable physical-device
qualification separately through the shared device broker using the exact verified
CI APK. A changed production default needs successful current-base CI, independent
source review and the applicable physical-device/resource evidence. Synthetic unit
fixtures and source coverage checks cannot substitute for platform behavior or
payload inspection.

## Ordered next slices

1. Qualify the existing test-only baseline with retained raw API 30/API 36
   reports and matching input hashes. Obtain separate visible-UI and worker-memory
   measurements where the baseline does not measure them. Agree on measured
   regression budgets before changing the default.
2. Review the implemented still-worker lifetime candidate against the failure
   matrix above, then obtain exact-head platform and matched performance evidence.
   Keep default adoption held while those gates remain pending. Preserve unresolved
   startup/death admission and the separate animation behavior.
3. Run the A/V capability prototype and publish its API/format decision. Review
   a narrow video preparation table, then integrate one exact-byte preparation
   owner across all outgoing routes without raw fallback.
4. Adopt MDK #2512 when its shared contract is available, then qualify Android
   settings and acquisition behavior together. Complete document processor and
   preservation decisions before adding a privacy-preparation implementation.

Keep the issue checklist open in this PR until each stated acceptance boundary
has evidence. A useful partial hardening result should name its remaining
platform, format and threat-model limits.

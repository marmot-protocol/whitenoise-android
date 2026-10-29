# Multimedia containment

## Android decoder boundary

Still-image decode and inspection, EXIF inspection and SVG preparation use a nonexported `android:isolatedProcess` service. The worker has a separate UID and no application permissions. Application startup skips account state, diagnostics, locale preferences, playback, transfers and background scheduling in that process.

Still images use one serialized lane that gives every untrusted input its own short-lived worker process; see [Per-input worker generations](#per-input-worker-generations). Video poster/duration probes and voice waveform extraction use a separate, process-wide serialized platform-service adapter. Android denies `media.player` and `media.extractor` discovery to isolated UIDs on API 30; attempting those operations in the image worker is unsupported. This is an explicit route, with no isolated-attempt/privileged-fallback sequence. A slow waveform must not occupy the notification-avatar lane. Production calls on the main looper or without a configured transport fail closed. JVM tests explicitly inject their codec fixture; those tests do not prove process isolation.

Input bytes are written only after their temporary file is unlinked, then passed by read-only descriptor. Picked images and media use a process-wide staging gate, with provider cancellation and bounded reads. Whole-file seekable videos pass their descriptor without a full-video array copy. Provider subranges are copied into their own unlinked inode, so a decoder cannot read bytes outside the selected asset. No URLs, account objects, keys or general file-access callback enter the worker.

The app owns bounded output shared memory. Replies use an explicit primitive Binder format with a per-request callback capability, kernel caller UID, first-response fencing. The app validates dimensions, byte capacity, image metadata and output protection, then copies software ARGB8888 pixels into an app-owned bitmap. It never displays mutable worker memory directly or re-decodes compressed image output. Shared-memory protection cannot revoke a worker's existing writable mapping; the bounded copy confines concurrent mutation to pixel values.

Debug instrumentation may pass the exact path of an existing synthetic `media-containment-canary`. It tests that the worker cannot reopen that file, including permission denial or namespace invisibility. No real account files are probed, and release requests omit this field.

## Limits and failure

- Encoded image input: at most 64 MiB. Existing receive/presentation limits may be lower and remain in force.
- Static source canvas: at most 32768 pixels per edge and 200 million pixels before sampled raster allocation.
- Output edge: at most 4096 pixels; parent-owned output: at most 64 MiB. Existing avatar, thumbnail, viewer and editor profiles remain tighter.
- Region decoding also caps the power-of-two intermediate at 16,777,216 pixels. Editor planning uses the same calculation before IPC so a budget refusal retains the processing-limit message; the worker still enforces the cap. Requested quality is preserved rather than silently undersampled to evade this limit.
- Existing GIF/WebP canvas/frame/work admission precedes every still or animated decode. A refused animation cannot bypass admission through its first frame.
- File media probes: at most 512 MiB, with validated descriptor offsets and lengths; this is not an attachment transfer quota.
- Image worker watchdog: 6 seconds, with an 8-second host wait. Every request retires its worker after one reply; an interrupted dispatched request retires it too. A worker still alive 250 ms after RETIRE is unbound, so Android stops its isolated process. Until that worker's Binder death arrives, no other input is admitted.
- Platform media: one process-wide operation, admitted before opening/staging inputs. Caller waits are bounded at 8 seconds for probes and 40 seconds for waveforms, plus bounded admission. Audio loops check cancellation and a 30-second decode ceiling. A timed-out native call keeps its slot until it actually terminates; account reset cannot create a parallel replacement thread. Late posters are recycled and retired results are discarded. The app cannot safely force-kill an in-process native call.
- Failed source revisions have bounded in-memory quarantine. Account teardown retires every image worker generation, clean spares included, as well as media delivery and pending reply capabilities; host cache-generation checks remain authoritative for UI publication.

Native temporary allocations are not bounded solely by output dimensions. Worker isolation and watchdogs contain ordinary faults but cannot force a compromised worker to obey its own watchdog or prevent a kernel/system-service escape. A malicious provider that ignores cancellation can hold one admitted off-main staging operation; new readers must not bypass its gate.

Android media services perform platform extraction and codec work, but their JNI/Binder clients remain in the app process. Descriptor bounds and serialized deadlines reduce exposure; they do not provide the image worker’s UID/private-data/network boundary. Platform metadata is validated and waveforms must contain exactly 64 finite normalized values.

The host rechecks source ownership and cancellation when a provider returns a
descriptor, before staging its bytes or reading descriptor metadata. A provider
that ignores cancellation cannot make a retired source enter those operations.

Received-audio transcription reads an already materialized attachment through a
separate serialized platform-codec adapter. It converts admitted mono/stereo
PCM16 to 16 kHz mono, preserving the resampling phase across codec buffers, and
feeds one continuous descriptor-backed request to the explicitly selected Offline
Speech to Text 0.21.0 or later service on Android 13+. Earlier builds require an
update before decoding or binding: their recording-only behavior is not qualified.
Service eligibility and the minimum version are rechecked before binding after
decoder preparation. Closing the writer after successful decoder EOF completes
the utterance. Decode/feed errors and unrecognized audio cannot
produce a successful partial transcript. The provider owns its downloaded model;
White Noise neither captures microphone audio nor falls back to another recognizer.
Transcripts remain transient dialog state and are cleared on dismissal, background,
source disposal or account/chat replacement. This path does not add MDK persistence
or claim permissionless decoder isolation. Run `MED-038` against the exact provider
and candidate APK; source contracts alone do not prove recognition compatibility.

The opt-in `AudioTranscriptionDeviceTest` uses Kokoro `af_heart` speech in a
27-second, 48 kHz stereo Opus fixture, with 18 seconds of inserted silence.
It checks `Hello world` before the pause, `Good morning` after it, and clearing
the result on Close.

HEIF/HEIC is not qualified by the JPEG/PNG tests. Some Android image implementations internally need media services unavailable to an isolated UID. Such formats fail closed if unsupported, without an app-process image fallback.

## Automatic versus explicit parsing

Remote timeline/GIPHY images and profile/list avatar overlays start with isolated first frames. Admitted native animation is enabled in the user-opened current media/avatar viewer or through the bounded inline received-image grant below, with visibility, motion and lifecycle controls. Composer previews also use isolated still frames because an external share intent does not attest a playback gesture. Outgoing original animation bytes retain their existing format/admission contract.

Explicit GIF/WebP playback uses a separate nonexported permissionless animation worker. The app-owned drawable implements Animatable and contains copied raw pixels only. One session is admitted process-wide, with a 1080-pixel edge ceiling and one outstanding frame. The worker drives software AnimatedImageDrawable scheduling, with a minimum 32-millisecond frame delay. Native animation canvas/frame/work limits still apply before parsing and are checked again against the native header. API 30 refused the later CTS EXIF-rotated WebP fixture even without decoder downscaling; ordinary WebP playback and that compatibility refusal are qualified separately, without accepting incomplete frames or using an app codec fallback. The parent validates kernel UID, session, sequence, primitive flags and output bounds, copies pixels into its own bitmap, and acknowledges only after consumption. The reusable parent-owned shared buffer remains writable by the worker; no immutable-memory claim is made. Concurrent worker mutation can affect bounded pixel values, not the app-owned drawable or its dimensions. Native decode/draw has a six-second independent watchdog; unacknowledged frames have an eight-second deadline. Healthy paused sessions remain bound until their viewer disposes them. Disposal/account reset invalidates callbacks and requests retirement; only Binder/system-confirmed death reopens the process slot. Image parsing uses its independent lane and can continue during animation.

Inline received GIF/WebP playback uses the same worker, with a stricter 512-pixel output edge. GIPHY cards, standalone attachments and album tiles share one FIFO presentation grant. Only an admitted first-frame result can request promotion; it reads existing GIPHY cache or authenticated local/retained attachment bytes and never starts a download. A grant lasts until its row loses eligibility. Other visible rows retain their still image, without a loading spinner or an animation-success claim. Refused sources keep their existing failure placeholder; unavailable/evicted bytes or worker refusal retain the admitted still and retry only on a later eligibility lifetime.

Inline eligibility requires a visible row, a resumed lifecycle, focused window, system motion enabled, unchanged owning account/runtime, unlocked app and no global media pause. Manual download consent does not override playback pause. Opening a viewer transfers focus, cancels inline work and closes its drawable before replacement worker admission; the native one-worker/death gate remains authoritative. Source replacement and cancellation close late decode results, while the retired drawable rejects late pixels. No second animated-byte cache or additional worker is introduced. Profile avatars and composer previews retain their existing explicit-viewer/first-frame policies.

A startup timeout retains animation admission even before OPEN: an unconnected Android instance may still be starting. This favours the one-worker resource bound over automatic recovery from an unconfirmed startup. If Android never reports a connection or worker death, the animation lane can remain unavailable until app-process restart; the still-image lane remains independent.

Received and picked video playback uses local file/content inputs and an explicit progressive source, rather than choosing a network/playlist protocol from its suffix. Media3 container extractors still execute in the app during explicit playback. Platform codec isolation is useful but does not isolate every Media3 parser. Referenced Nostr-event networking retains its existing owner and explicit loading policy.

## MDK boundary

MDK retains reference validation, authenticated encryption/hash verification, safe public-address/TLS/redirect transport, transfer policy, resumable acquisition and retained state. Native receive memory and low-space recovery remain tracked by [MDK #2241](https://github.com/marmot-protocol/mdk/issues/2241) and [MDK #2239](https://github.com/marmot-protocol/mdk/issues/2239). Shared public-image qualification/admission remains [MDK #2232](https://github.com/marmot-protocol/mdk/issues/2232). This Android boundary neither duplicates those rules nor claims an upstream fix is shipped.

## Qualification

Unit fixtures cover pixel limits, malformed bytes, primitive reply validation and explicit fail-closed transport. Hosted instrumentation must prove real UID separation, pixel fidelity, malformed-input recovery, account-reset behavior and real descriptor-based SVG/EXIF operations. Separate platform tests qualify video poster/duration and PCM waveform behavior; those tests do not attest image-worker isolation for media services. Physical testing additionally checks OS process identity, private-data/network denial, hung-worker death, descriptor/RSS stability, normal image/animation compatibility, SVG, poster/waveform output and large seekable video sources. Record exact source and APK bytes; installation alone is not feature coverage. API 30 compatibility needs its own emulator/device evidence. Do not treat unchecked manual cases or a JVM fake as completed device proof.

## Photo compatibility and budget refusals

Inspection and the editor share a 200-million-pixel source limit and a 32768-pixel
source-edge limit. Decoded output remains capped at 4096 pixels on its longest edge
and 64 MiB. A tight crop may require a larger full-source decode even when its final
output is smaller: map the crop into outward-rounded encoded-source coordinates
and request an isolated BitmapRegionDecoder subset. Keep its encoded origin in the
final EXIF/rotation transform. The power-of-two intermediate has its own 16-Mi-pixel
ceiling before native allocation; transferred output retains the 4096-pixel/64-MiB
ceiling. Unsupported regions or budgets refuse while retaining the previous
attachment. Do not clamp a full-image preview and upscale it into misleading detail.
The editor budgets the shared pixel buffer and returned host bitmap concurrently,
as well as rendering and encoding peaks.

Unavailable waveform results are not cached: contention, startup or account reset
may be transient. Successful waveforms retain their bounded cache. SVG rasterization
checks the final host-encoded PNG against the existing upload size limit as well as
the worker's encoded output. Restored video sources outside file/content schemes
are refused before player construction and use the existing filename preview.
Instrumentation artifacts are labelled with the built checkout SHA: a PR-triggered
run normally tests its merge revision, while a branch dispatch tests that branch.

Unavailable EXIF replies remain unknown. Original-quality and animated metadata
preparation refuse that operation instead of stripping orientation under an
unconfirmed upright assumption. A later healthy request may retry normally.

Optional notification emoji decoding is interruptible so its short artwork deadline
can continue to text-only posting while the image worker is busy. Cancellation
recycles undelivered pixels and preserves the account/notification ownership checks.

The hosted MediaContainmentAcceptance campaign includes malformed JPEG/PNG/EXIF/SVG,
actual malformed isolated-UID Binder replies, selected-input write denial,
changing GIF/WebP pixels, pause/resume, account reset, a row-encoded synthetic
108-megapixel region and an unmodified CTS HEIF fixture. Record each API's HEIF
inspect/preview/region outcome; a safe refusal is not successful format support.
These controlled fixtures do not replace ordinary messenger or actual camera
corpus qualification, including 200-megapixel and ten-bit HEIF sources. MED-032
retains the separate manual acceptance.

## Per-input worker generations

Each still-image request — decode, inspection, EXIF, region or SVG, from avatars,
notification artwork, thumbnails, composer/editor sources and viewers alike —
runs in its own worker generation (#3373). A generation is one
`bindIsolatedService` binding with a unique instance name (`images_<n>`). The
parent links a death recipient to its Binder before sending any descriptor. It
carries one input; there is no same-source grouping, so inspecting and then
decoding the same bytes uses two generations.

- The reply capability belongs to that generation and request. It accepts one
  reply and is revoked before RETIRE; later replies, including those from an
  abandoned or reset request, are refused by the transaction itself.
- After the reply the parent sends RETIRE. The worker's own exit normally
  follows within a few milliseconds. A worker still alive 250 ms later is
  unbound, and Android stops the isolated process (`isolated not needed`) whether
  or not the worker cooperates. Unbinding at once was measured to slow the common
  path; neither step counts as evidence of death.
- Only the generation's kernel Binder death callback reopens admission. The
  pixel copy waits for the producing generation's death. The next input also
  waits for it, even when its request was abandoned, timed out or reset.
- Up to two clean spares are bound while the current input is served. They
  have never received input and are retired on account reset or close. Live
  generations, including retired ones whose death is unconfirmed, never exceed
  three, plus the application zygote.
- A refused bind fails that input. A throwing bind, a startup without a
  connection, a death link that cannot be registered or a worker that never
  dies keeps its slot; when every slot is held, new input fails closed rather
  than starting another process. A late connection from a timed-out startup is a
  clean spare. Nothing falls back to an earlier worker or to app-process decoding.

The worker starts from the app's own application zygote
(`android:useAppZygote`, `MediaZygotePreload`). That zygote has its own isolated
UID, receives no descriptors, account state or untrusted bytes, and preloads
only worker classes with constant static state; it starts no threads. Every
generation is a fresh fork with its own isolated UID. Forks share the zygote's
address-space layout for preloaded code. The platform zygote's preloaded
libraries, including the codecs, were already shared this way across all app
processes.

Animation sessions keep their separate worker, admission and lifetime rules.
Platform audio/video keeps its separate adapter.

### Why this granularity

Measured with `MediaWorkerThroughputDeviceTest` on API 30 and API 36 arm64
emulators (4 vCPUs, 2 GiB, debug build of this change), in ABBA order,
pooled. Before is the pre-#3373 reusable worker on a build without the
application zygote, as `master` shipped; after is the default. Values are
p50/p95 milliseconds; queue wait is the p95 admission wait.

| Scenario | API 30 before | API 30 after | API 36 before | API 36 after |
| --- | ---: | ---: | ---: | ---: |
| Cold first thumbnail, new lane | 326/462 | 42/69 | 234/322 | 43/76 |
| Idle thumbnail, 400 ms apart | 2/4 | 8/12 | 2/4 | 9/13 |
| Idle 1920×1440 viewer | 15/22 | 21/26 | 12/15 | 20/26 |
| Six-operation mix, back to back | 1/26 | 12/43 | 1/24 | 18/51 |
| Burst of 24 (queue wait) | 16/27 (26) | 162/295 (288) | 19/30 (29) | 216/400 (381) |
| Burst of 48 (queue wait) | 30/52 (51) | 332/622 (612) | 35/83 (81) | 381/736 (718) |
| Burst of 96 (queue wait) | 57/105 (104) | 619/1181 (1174) | 54/99 (98) | 884/1589 (1580) |
| Scroll, 8 tiles every 400 ms | 7/13 | 56/99 | 8/13 | 67/128 |
| No result or admission timeout | 0 | 0 | 0 | 0 |
| Live image workers, at most | 1 | 3 | 1 | 3 |
| Idle PSS, workers and zygote (MiB) | 50 | 74 | 65 | 80 |

Caller FD maxima were unchanged. The first image after the application zygote
starts costs about 0.4 s on API 30, like the old worker's cold start; the
zygote stays up while a spare is alive and exits five seconds after its last
child.

Rejected alternatives, on the same emulators:

- A cold isolated process per input took 240–320 ms to connect. Requests took
  255–335 ms each, and 56 of 96 requests in a 48-request burst hit the
  eight-second admission deadline.
- Without the application zygote, even two spares left 96 of 192 (API 30) and
  64 of 192 (API 36) burst-of-96 requests timed out.
- With the zygote but no spare, a 48-request burst reached 1.3 s (API 30) and
  1.8 s (API 36) at p95. One spare reached 1.0 s and 1.4 s, and 2.8 s for 96
  requests on API 36.
- Preloading the worker classes cut a fresh worker's first decode from about
  10 ms to 3 ms. Unbinding together with RETIRE raised the death wait from
  2.4 ms to 48 ms at p95, so unbinding is only an escalation.
- Same-source grouping and visible-first priority were not needed to meet the
  budget and would add admission state.

The adoption budget: no request may fail or miss the admission deadline in any
scenario; idle thumbnail p95 within 50 ms and viewer p95 within 100 ms of
before; cold first thumbnail p95 within 150 ms of before; burst p95 within
0.6 s (24), 1.2 s (48) and 2.5 s (96), under a third of the deadline; scroll
p95 within 250 ms; at most three live workers plus the zygote; no caller FD
growth; idle PSS growth within 40 MiB. Two spares meet it on both APIs. One
spare missed the burst limits on API 36 (0.68 s, 1.4 s and 2.8 s), so the
default is two. These are emulator debug figures; the budget still needs
release-build and physical-device confirmation.

### Residual risks

- A request waiting for pixels escalates an uncooperative worker itself. After
  an abandoned or reset request, a worker that ignores RETIRE stays alive until
  the next request or close escalates it. It has no capability and receives no
  input.
- A cooperative self-exit ends the worker process only. Android's escalation
  kill covers the process group. A forked descendant of a compromised worker is
  outside the Binder witness.
- Heap tagging through the application-zygote launch path is unqualified on
  MTE hardware; see [native heap memory tagging](native-heap-memory-tagging.md).
- Forks share the application zygote's layout for preloaded code, so one
  generation's address-space disclosure applies to later ones.
- Two idle spares and the application zygote stay resident while the app runs.
- There is no visible-first priority. Callers cancelled through interruption,
  such as the image bubble decode, leave the queue; other callers wait within
  the eight-second admission bound.
- Timings are emulator debug builds. Release-build, physical-device and
  GrapheneOS figures are still required.

The test-only [still-worker baseline](media-worker-baseline.md) records the
historical reuse characterization and the matched throughput harness. The
[still-image worker lifetime](media-worker-lifetime.md) page records the owner,
deadline and test boundaries.

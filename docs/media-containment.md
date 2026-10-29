# Multimedia containment

## Android decoder boundary

Still-image decode and inspection, EXIF inspection and SVG preparation use a nonexported `android:isolatedProcess` service. The worker has a separate UID and no application permissions. Application startup skips account state, diagnostics, locale preferences, playback, transfers and background scheduling in that process.

Each still-image input runs in its own lazily bound, serialized worker generation; see [Per-input worker generations](#per-input-worker-generations). Video poster/duration probes and voice waveform extraction use a separate, process-wide serialized platform-service adapter. Android denies `media.player` and `media.extractor` discovery to isolated UIDs on API 30; attempting those operations in the image worker is unsupported. This is an explicit route, with no isolated-attempt/privileged-fallback sequence. A slow waveform must not occupy the notification-avatar lane. Production calls on the main looper or without a configured transport fail closed. JVM tests explicitly inject their codec fixture; those tests do not prove process isolation.

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
- Image worker watchdog: 6 seconds, with an 8-second host wait. An interrupted dispatched request also asks its worker to retire through its control looper. A worker still alive after timeout/cancellation blocks the next input; a system-confirmed death permits a fresh generation.
- Platform media: one process-wide operation, admitted before opening/staging inputs. Caller waits are bounded at 8 seconds for probes and 40 seconds for waveforms, plus bounded admission. Audio loops check cancellation and a 30-second decode ceiling. A timed-out native call keeps its slot until it actually terminates; account reset cannot create a parallel replacement thread. Late posters are recycled and retired results are discarded. The app cannot safely force-kill an in-process native call.
- Failed source revisions have bounded in-memory quarantine. Account teardown retires the image worker generations and their spares, media delivery and pending reply capabilities; host cache-generation checks remain authoritative for UI publication.

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

Each still-image request — decode, inspection, EXIF, region or SVG, from
avatars, notification artwork, thumbnails, composer/editor sources and viewers
alike — runs in its own worker generation (#3373). A generation is one
`bindIsolatedService` binding with a unique instance name. The parent links a
death recipient to its Binder before sending any descriptor. It carries one
input; there is no same-source grouping, so inspecting and then
decoding the same bytes uses two generations.

- The reply capability belongs to that generation and request. It accepts one
  reply and is revoked before RETIRE; later replies, including those from an
  abandoned or reset request, are refused by the transaction itself.
- After the reply the parent sends RETIRE. The worker's own exit normally
  follows within a few milliseconds. A worker still alive 250 ms later is
  unbound on the parent's own timer, even when no request is waiting, and
  Android stops the isolated process (`isolated not needed`) whether or not the
  worker cooperates. Unbinding at once was measured to slow the common
  path; neither step counts as evidence of death.
- Only the generation's kernel Binder death callback reopens admission. The
  pixel copy waits for the producing generation's death. The next input also
  waits for it, even when its request was abandoned, timed out or reset.
- A dispatched request that ends without a valid result — a worker crash, a
  watchdog kill, a deadline miss or a refusal — quarantines its source bytes
  for 60 seconds, as the reusable lane does, so one hostile source cannot fork
  a worker or hold the lane on every request. Caller interruption and account
  reset are not evidence about the source and record nothing.
- Up to two clean spares are bound while the current input is served. They
  have never received input and are retired on account reset or close. Live
  generations, including retired ones whose death is unconfirmed, never exceed
  three.
- A refused bind fails that input. A binding Android reports dead or null
  before it ever connects never received input: it is unbound and its slot
  freed. A throwing bind, a startup that reports nothing, a death link that
  cannot be registered or a worker that never dies keeps its slot; when every
  slot is held, new input fails closed rather than starting another process.
- A claim waits at most one second for a spare that is still starting while a
  slot is free, then starts a fresh generation. A late connection from a slow
  start becomes a clean spare. Nothing falls back to an earlier worker or to
  app-process decoding.

Animation sessions keep their separate worker, admission and lifetime rules.
Platform audio/video keeps its separate adapter.

### Launch path

The still-image service sets `android:useAppZygote` with `MediaZygotePreload`;
both lanes launch through it. The preload runs before any worker exists,
receives no descriptors, account state or untrusted bytes, and only initializes
worker classes with constant static state; it starts no threads. What that
means for each worker differs by OS:

- Stock Android, including the emulators measured here: Android starts one
  application zygote for the app, with its own isolated UID, and forks every
  worker from it. Each fork gets its own isolated UID but shares the zygote's
  address-space layout for everything loaded before the fork, including the
  preloaded worker classes. The platform zygote's preloaded libraries, the
  codecs among them, were already shared this way across all app processes.
- GrapheneOS with exec spawning, its default: Android
  [skips application zygotes for isolated services](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/services/core/java/com/android/server/am/ActiveServices.java#L6349-L6363)
  ("app zygotes are pointless when exec spawning is used"). Each worker is
  exec-spawned with a fresh layout, and `MediaZygotePreload`
  [runs inside that worker](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/core/java/com/android/internal/os/ExecSpawning.java#L107-L117),
  in the isolated_app domain, before its service starts. This was read from
  the GrapheneOS 17 source on 29 October 2026, not observed on a device. Generations share no layout, and the fork start
  cost measured on stock Android does not apply there.
- GrapheneOS with exec spawning turned off for the app behaves like stock
  Android.

### Measurements and proposed budget

Measured on API 30 and API 36 arm64 emulators (4 vCPUs, 2 GiB and 2.4 GiB)
with debug builds of this change. *Before* is the reusable worker on a build
that differs only by dropping the application-zygote attributes, which is how
`master` ships it. *After* is the per-input lane with two spares, launched
through the application zygote. Before and after ran from separate APKs on
the same emulator within the same hour, with each APK's variants in ABBA
order. The throughput rows come from `MediaWorkerThroughputDeviceTest`, as
p50/p95 milliseconds; the queue wait is the p95 admission wait. The UI rows
come from `MediaWorkerUiThroughputDeviceTest`; see
[the still-worker baseline](media-worker-baseline.md#matched-throughput-comparison).

| Throughput | API 30 before | API 30 after | API 36 before | API 36 after |
| --- | ---: | ---: | ---: | ---: |
| First image after launch, cold application zygote | 324/367 | 401/500 | 228/280 | 274/325 |
| First image on a new lane, application zygote still warm | 320/333 | 39/78 | 227/259 | 37/44 |
| Idle thumbnail, 400 ms apart | 4/7 | 16/20 | 5/9 | 12/18 |
| Idle 1920×1440 viewer | 27/32 | 34/49 | 23/32 | 28/61 |
| Six-operation mix, back to back | 1/26 | 19/42 | 1/24 | 23/60 |
| Burst of 24 (queue wait) | 28/44 (42) | 196/369 (348) | 32/48 (46) | 280/501 (492) |
| Burst of 48 (queue wait) | 46/71 (70) | 392/743 (723) | 50/77 (76) | 489/900 (892) |
| Burst of 96 (queue wait) | 69/115 (113) | 797/1512 (1494) | 65/113 (112) | 1058/1992 (1964) |
| Scroll, 8 tiles every 400 ms | 13/20 | 65/125 | 12/21 | 79/147 |
| No result or admission timeout | 0 | 0 | 0 | 0 |
| Live image workers, at most | 1–2 | 3 | 1–2 | 3 |
| Idle PSS, workers and zygote (MiB) | 47.8 | 70.6 | 59.5 | 74.0 |
| Caller FDs, at most | 64 | 64 | 104 | 106 |

Before, the first image after launch and the first on a new lane are the same
cold isolated start. After, only the first image after launch pays for the
application zygote; a new lane while the zygote is still up is about 0.2–0.3 s
faster than before. Before reaches two workers only while a closed lane's
worker exits.

UI times are milliseconds.

| UI level | API 30 before | API 30 after | API 36 before | API 36 after |
| --- | ---: | ---: | ---: | ---: |
| Grid of 120 tiles, passes | 6 | 6 | 6 | 6 |
| Tiles loaded while on screen, median per pass | 33 | 99 | 66 | 110 |
| Tiles that failed | 0 | 0 | 53, in 3 passes | 0 |
| Visible tiles still blank 10 s after scrolling stops | 0 | 0 | 14, in 1 pass | 0 |
| Tile p50/p95, median of passes | 268/422 | 156/441 | 10/383 | 160/542 |
| Slowest tile | 1050 | 605 | 1052 | 10834 |
| Frame p95, median of passes | 18 | 19 | 45 | 43 |
| Notification avatar, idle, p50/worst p95 | 12/18 | 60/67 | 13/19 | 67/106 |
| Notification avatar behind a 24-image burst, p50/worst p95 | 42/60 | 480/589 | 46/56 | 608/1235 |
| Emoji artwork within 250 ms, idle | 60 of 60 | 60 of 60 | 60 of 60 | 60 of 60 |
| Emoji artwork within 250 ms, behind a 24-image burst | 60 of 60 | 0 of 60 | 60 of 60 | 0 of 60 |
| Viewer cancelled mid-decode: pixels delivered | 0 of 10 | 0 of 10 | 0 of 10 | 0 of 10 |
| Next image after a viewer cancel, p50 (first attempt succeeded) | 395 (0 of 10) | 59 (10 of 10) | 315 (0 of 10) | 50 (10 of 10) |
| Editor cancel, then next image, p50 | 17, 19 | 25, 33 | 14, 16 | 26, 54 |

Before, the reusable lane retires its worker for every tile that leaves the
screen mid-decode and starts a cold one, so most tiles of a fast scroll never
load. On API 36 it also failed 17 or 18 tiles in three of six passes. After a
cancelled viewer decode, its next request returned nothing and only the
scenario's retry succeeded. The 10.8 s tile came from one API 36 pass that
ran at a 47 ms frame p50 against 17 ms in the others. Its start was not
recorded. Four later passes recorded the slowest tile's start, and none
exceeded 0.7 s. The outlier is unexplained.

The budget below is proposed, not approved. Status is after against before
on each API.

| Proposed budget item | API 30 | API 36 |
| --- | --- | --- |
| No failed request or admission timeout in any throughput scenario | Met | Met |
| Idle thumbnail p95 within 50 ms of before | Met, +13 | Met, +9 |
| Idle viewer p95 within 100 ms of before | Met, +17 | Met, +29 |
| First image after launch p95 within 150 ms of before | Met, +133 | Met, +45 |
| First image on a new lane p95 within 150 ms of before | Met, −255 | Met, −215 |
| Burst p95 within 0.6, 1.2 and 2.5 s for 24, 48 and 96 requests | Met: 0.37, 0.74, 1.51 s | Met: 0.50, 0.90, 1.99 s |
| Scroll-round p95 within 250 ms | Met, 125 | Met, 147 |
| At most three live workers plus the application zygote | Met | Met |
| No caller FD growth across scenarios | Met, 64 throughout | Met, a constant two more (106 at most) |
| Idle PSS growth within 40 MiB | Met, +22.8 | Met, +14.5 |
| Grid: no failed tile and no visible tile left blank | Met | Met |
| Grid frame p95 within one 60 Hz frame of before | Met, +1 | Met, −2 |
| Notification avatar within its 2.5 s bound, idle and behind a burst | Met | Met |
| Emoji artwork within its 250 ms bound, idle | Met | Met |
| Emoji artwork within its 250 ms bound behind a 24-image burst | **Not met**, 0 of 60 | **Not met**, 0 of 60 |
| Cancelled viewer and editor decodes deliver nothing, the next image arrives within 2 s | Met | Met |

The grid, notification and cancellation items were added with these UI
measurements; the others predate them. Two results need a decision rather than
a tick:

- Emoji artwork behind a burst. Each notification's emoji waits behind the
  queued grid decodes, roughly half a second for 24, and the presenter then
  posts the notification as text without the emoji image. The reusable lane
  clears the same queue within the bound. The options are to accept it, to
  admit notification artwork ahead of grid decodes (visible-first priority,
  which this change does not implement), or to keep the reusable default.
- The first image after launch. It is 77 ms (API 30) and 46 ms (API 36)
  slower at p50, within the proposed bound but a regression. Only images on a
  lane opened while the application zygote is still up are faster.

These are emulator debug figures. Release builds, physical devices,
GrapheneOS and API 34 (no local emulator image) are unmeasured.

Rejected alternatives, on the same emulators:

- Two spares without the application zygote: burst-of-24 p95 of 3.7 s
  (API 30) and 2.8 s (API 36), 94 and 58 of 192 burst-of-96 requests past the
  eight-second admission deadline, and scroll-round p95 of 7.4 s and 4.6 s.
- The application zygote with no spare: burst-of-48 p95 of 1.47 s (API 30) and
  1.69 s (API 36). With one spare: 0.58, 1.15 and 2.33 s (API 30) and 0.65,
  1.30 and 2.65 s (API 36) for 24, 48 and 96 requests. API 36 misses all three
  limits, so the lane keeps two spares.
- In earlier runs on the same emulators, preloading the worker classes cut a
  fresh worker's first decode from about 10 ms to 3 ms. Unbinding together
  with RETIRE raised the death wait from 2.4 ms to 48 ms at p95, so unbinding
  is only an escalation.
- Same-source grouping and visible-first priority are not implemented.
  Visible-first priority is the change that would address the emoji result.

### Default

Production installation uses per-input generations. The default was switched
in #3516 pending approval of the [proposed budget](#measurements-and-proposed-budget),
including its unmet emoji item. `ReusableMediaTransport` is no longer
installed. It stays in production code as the lane to restore if that budget is
rejected and as the measurement baseline. Removing it is a follow-up after that
decision and after #3439, which edits the facade's animation type checks.

### Residual risks

- A cooperative self-exit ends the worker process only. Android's escalation
  kill covers the process group. A forked descendant of a compromised worker is
  outside the Binder witness.
- Two idle spares stay resident while the lane is installed, plus the
  application zygote on stock Android.
- On stock Android, forks share the application zygote's layout for preloaded
  code, so one generation's address-space disclosure applies to later ones.
- Heap tagging through the application-zygote launch path is unqualified on
  MTE hardware; see [native heap memory tagging](native-heap-memory-tagging.md).
- Worker start cost on GrapheneOS, where every worker is exec-spawned, is
  unmeasured.
- There is no visible-first priority. Callers cancelled through interruption,
  such as the image bubble decode, leave the queue; other callers wait within
  the eight-second admission bound. Emoji artwork misses its 250 ms bound
  behind a burst; see the [proposed budget](#measurements-and-proposed-budget).

The test-only [still-worker baseline](media-worker-baseline.md) records the
historical reuse characterization and the matched throughput harness. The
[still-image worker lifetime](media-worker-lifetime.md) page records the owner,
deadline and test boundaries.

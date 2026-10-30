# Multimedia containment

## Android decoder boundary

Still-image decode and inspection, EXIF inspection and SVG preparation use a nonexported `android:isolatedProcess` service. The worker has a separate UID and no application permissions. Application startup skips account state, diagnostics, locale preferences, playback, transfers and background scheduling in that process.

The app decodes still images in domain-bound worker generations: each isolated worker serves one conversation's member-pinned media, the account's public profile pictures, one conversation's outgoing picks or the account's display-only local files, and any other input gets a single-use worker; see [Domain-bound worker generations](#domain-bound-worker-generations). Video poster/duration probes and voice waveform extraction use a separate, process-wide serialized platform-service adapter. Android denies `media.player` and `media.extractor` discovery to isolated UIDs on API 30; attempting those operations in the image worker is unsupported. This is an explicit route, with no isolated-attempt/privileged-fallback sequence. A slow waveform must not occupy the notification-avatar lane. Production calls on the main looper or without a configured transport fail closed. JVM tests explicitly inject their codec fixture; those tests do not prove process isolation.

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
- Image worker watchdog: 6 seconds, with an 8-second host wait. An interrupted dispatched request also asks the worker to retire through its control looper. A worker still alive after timeout/cancellation blocks replacement admission; a system-confirmed death permits a fresh generation.
- Platform media: one process-wide operation, admitted before opening/staging inputs. Caller waits are bounded at 8 seconds for probes and 40 seconds for waveforms, plus bounded admission. Audio loops check cancellation and a 30-second decode ceiling. A timed-out native call keeps its slot until it actually terminates; account reset cannot create a parallel replacement thread. Late posters are recycled and retired results are discarded. The app cannot safely force-kill an in-process native call.
- Failed source revisions have bounded in-memory quarantine. Account teardown retires the image worker, media delivery and pending reply capabilities; host cache-generation checks remain authoritative for UI publication.

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

## Domain-bound worker generations

A still-image worker generation serves one data-access domain for its whole
life (#3373). Callers name the domain of each request through
`MediaTrustDomain`; the facade and the transport interface carry it unchanged.

Every domain keeps one rule: each input's bytes are fixed by a principal who
can already read the whole domain, and whatever the worker returns stays where
that principal can already see it. A decoder compromised by one input then
reaches only content its own author could already read, and its output reaches
no one who could not already see that domain. Bytes that a URL's host chooses,
with no digest a member pinned, never enter a conversation's worker. Anything a
caller cannot place under the rule decodes single-use.

| Domain | Key | Inputs |
| --- | --- | --- |
| Conversation | account, conversation | Bytes a member fixed: encrypted, hash-verified message media, thumbnails, bubbles and the viewer; custom emoji received as the message's own attachments; link-preview JPEGs whose digest the message pins, checked before decode; and the group's encrypted, hash-pinned image wherever it is drawn, in the chat list, durable chat rows and the notification loader included. |
| Public profiles | account | Kind-0 profile pictures and banners, of people in every conversation and of the user's own accounts. They are published to relays, so anyone can fetch all of them and anyone may choose their bytes; sharing one worker exposes nothing new. A group's image is never one of these. Every caller names this domain explicitly. |
| Outgoing | account, conversation | Images the user picked from the device to send into one conversation, while unedited: composer picks and previews, the editor's source inspection, staged drafts, Original-quality preparation, thumbhashes and just-sent thumbnails. The worker's output is sent there, so it never sees another conversation's picks. An edited render (crop, turn, blur or marks) and the editor's own preview decode single-use instead. |
| Local | account | Display-only files of this device: the user's custom-emoji files, notification emoji included, and stored private contact pictures. Their decoded pixels are drawn on this device or exported only to this user's own notification surfaces (notification emoji are re-encoded as PNG and shared with the system through the app's FileProvider); they are never sent, and an emoji is sent as its stored file. |
| Unique | none | Anything else. Each input gets its own single-use generation, as in the per-input lane. Today: GIPHY first frames, Nostr event and linked-note images, a group's URL picture (in a header, the chat list, a durable row whose group has a URL, or a notification), web image search results and typed image URLs, picks during sign-up, identity-picture cropping, profile and group picture picks, preparing a new private contact picture, the avatar viewer, and picker rows that may show either a group or a person. The avatar loader and the generic picture rows fall back to this when a caller names nothing. |

A group's picture decodes in its conversation only when it is the encrypted
image with no URL beside it (`GroupProjector.pictureIsMemberPinned`). A group
whose picture is a URL, or that has both a URL and an image hash, decodes it
single-use. A direct chat's peer picture is a published one.

Audit of the conversation rows against the rule, after round 3's review:

| Input | Who fixes the bytes | Domain |
| --- | --- | --- |
| Message media and its viewer | The sending member; the attachment's hash is verified before decode | Conversation |
| Received custom emoji | The sending member, as one of the message's attachments | Conversation |
| Link-preview JPEG | The linking member, through the digest in the URL, checked before decode | Conversation |
| Group's encrypted image | The member who set it, through the image hash and the group's key | Conversation |
| Group's URL picture | The URL's host | Unique (was Conversation) |
| GIPHY first frame | GIPHY, an allow-listed host, with no digest | Unique (was Conversation) |
| Nostr event and linked-note images | The note's author and its host | Unique (was Conversation) |
| Direct chat peer's picture in a header or notification | Its host; it is published | Public profiles (was Conversation) |
| Picks to send | The user, who chose each file for this conversation | Outgoing (was account-wide Local) |
| Edited renders and the editor preview | The user's pick, after a crop, turn, blur or marks | Unique (was Outgoing) |

Within its domain a compromised worker can still read every later input the
generation serves, including descriptors it retained; alter the pixels it
returns, which the parent validates for shape but not content; and use CPU and
memory up to its watchdog. Within the outgoing domain a hostile earlier pick
can carry another pick of the same conversation, including one removed from
the draft, into a sent image. Edited (cropped, blurred or marked) renders are
decoded single-use, so an earlier pick cannot undo a redaction. In the local
domain a hostile emoji file can draw a private contact picture in an emoji slot
on this device or in this user's own notification. Escapes through the kernel or
system services are not contained by domains.

Lifetime:

- A generation is one `bindIsolatedService` binding with a unique instance
  name. The parent links a death recipient to its Binder before sending any
  descriptor. Its first claim binds it to a domain, and it never serves another;
  a domain has at most one live generation.
- Each request has its own reply capability. It accepts one reply and is
  revoked before the generation is reused or retired; later replies are refused
  by the transaction itself.
- A valid reply returns a domain's generation to that domain. Its pixels are
  copied while it lives, as only the domain's own worker can touch them.
- A worker crash, a watchdog kill, a deadline miss or an invalid reply retires
  the generation and quarantines the source bytes for 60 seconds, as the
  reusable lane does, so one hostile source cannot fork a worker or hold its
  domain on every request. The domain's next input waits for the retired
  generation's Binder death. Caller interruption and account reset are not
  evidence about the source and record nothing.
- A generation is replaced after 64 inputs and retired after 30 seconds idle,
  so none serves its domain indefinitely and a quiet conversation frees its
  memory. A replacement costs one application-zygote fork on stock Android.
- Account reset and close retire every generation and spare.
- A Unique input gets a single-use generation, retired after its reply. Its
  Binder death precedes the parent pixel copy and the next Unique input.
- Retirement sends RETIRE first; the worker's own exit normally follows within
  a few milliseconds. A worker still alive 250 ms later is unbound on the
  parent's own timer, even when no request is waiting, and Android stops the
  isolated process (`isolated not needed`) whether or not it cooperates.
  Unbinding at once was measured to slow the common path; neither step counts
  as evidence of death.

Admission:

- Each domain has its own serialized admission, so a burst in one conversation
  never queues another domain's request. Unique requests share one lane.
  An admitted request claims its domain's worker after the source's quarantine
  check and before its output buffer or input exists, so a request waiting for
  a worker holds no descriptors, however many domains wait.
- At most three generations live at once, retired ones whose death is
  unconfirmed and clean spares included. One clean spare, which has never
  received input, is kept while a slot is free.
- A new domain takes the spare or a free slot. When every slot is taken, it
  evicts the least recently used idle domain, and only that worker's Binder
  death frees the slot; a busy domain is never evicted, and a slot already
  being freed is awaited rather than doubled by a second eviction.
- A refused bind fails that input. A binding Android reports dead or null
  before it ever connects never received input: it is unbound and its slot
  freed. A throwing bind, a startup that reports nothing, a death link that
  cannot be registered or a worker that never dies keeps its slot; when every
  slot is held, new input fails closed rather than starting another process.
- A claim waits at most one second for a spare that is still starting while a
  slot is free, then starts a fresh generation. Nothing falls back to an
  earlier worker, another domain's worker or app-process decoding.

Animation sessions keep their separate worker, admission and lifetime rules.
Platform audio/video keeps its separate adapter.

### Launch path

The still-image service sets `android:useAppZygote` with `MediaZygotePreload`;
every lane, the reusable one included, launches through it. The preload runs before any worker exists,
receives no descriptors, account state or untrusted bytes, and only initializes
worker classes with constant static state; it starts no threads. What that
means for each worker differs by OS:

- Stock Android, including the emulators measured here: Android starts one
  application zygote for the app, with its own isolated UID, and forks every
  worker from it. Each fork gets its own isolated UID but shares the zygote's
  address-space layout for everything loaded before the fork, including the
  preloaded worker classes. The platform zygote's preloaded libraries, the
  codecs among them, were already shared this way across all app processes.
- GrapheneOS, whose exec spawning is on by default
  ([`persist.security.exec_spawn`](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/core/java/android/ext/settings/ExtSettings.java#L24-L25),
  default `true`): Android
  [skips the application zygote for isolated services](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/services/core/java/com/android/server/am/ActiveServices.java#L6349-L6363)
  ("app zygotes are pointless when exec spawning is used"). Each worker is
  exec-spawned: a fresh `app_process` that replays the zygote's setup, with a
  fresh address-space layout, and `MediaZygotePreload`
  [runs inside that worker](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/core/java/com/android/internal/os/ExecSpawning.java#L107-L117),
  in the isolated_app domain, before its service starts. This is verified from
  the GrapheneOS 17 source, read on 29 October 2026, and not observed on a
  device. Generations share no layout. A worker start costs at least what the
  measurements below record without the application zygote, where the platform
  zygote still forks a warm process, and exec spawning does more work than that
  fork.
- GrapheneOS with exec spawning turned off for the app behaves like stock
  Android.

### Measurements and proposed budget

Measured on API 30 and API 36 arm64 emulators (4 vCPUs; 2 GiB and 2.4 GiB)
with debug builds of this change, on a shared host whose load average ran
between about 1 and 5. API 34, release builds and physical devices are
unmeasured. Five configurations, each column as it would ship:

- *Master*: the reusable worker on a build without the application-zygote
  attributes, which is how `master` ships it.
- *Reusable + zygote*: the same reusable worker on this change's build, which
  launches it through the application zygote. Keeping the reusable default
  ships this.
- *Per-input + zygote*: every request single-use with two spares, the lane this
  change proposed before domains (the domain transport with every request left
  undeclared).
- *Domain + zygote*: domain-bound generations as production configures them.
- *Domain, no zygote*: the same lane on the build without the application
  zygote. GrapheneOS skips the application zygote by default, so this is a
  lower bound for GrapheneOS; its exec-spawned starts cost more again.

Each APK ran once per emulator, with its lanes in ABBA order; the two APKs ran
within the same hour on the same emulator. Throughput rows come from
`MediaWorkerThroughputDeviceTest` as p50/p95 milliseconds, with the p95
admission wait in brackets; UI rows from `MediaWorkerUiThroughputDeviceTest`;
see [the still-worker baseline](media-worker-baseline.md#matched-throughput-comparison).
Every request in a single-conversation scenario belongs to one conversation.
The application zygote's effect is not attributed to isolation: compare the
domain lane with *Reusable + zygote*, which shares its launch path, and the
two no-zygote columns with each other.

The proposed budget below was written before the domain lane existed and is
judged unchanged. Runs 1 and 2 were stopped because they exposed defects:
requests waiting for a worker held descriptors, and a cross-domain admission
bound made domains evict each other. Run 3 completed and missed U3 on API 36,
2 of 60 idle avatars, which exposed a race between eviction and a domain's own
request; with that fixed, run 4 confirmed the budget. Run 4's data is shown
first, then the round-4 re-measure after the review's fixes. T11 is judged as
the budget states it: a lane's maximum caller FDs never above master's.

#### API 30

| Throughput, p50/p95 ms | Master | Reusable + zygote | Per-input + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: |
| First image after launch | 318/432 | 401/471 | 395/419 | 387/392 | 326/357 |
| First image, new lane | 315/327 | 26/29 | 39/69 | 24/29 | 328/345 |
| First image, new domain on a warm lane | 4/7 | 5/7 | 16/20 | 47/56 | 337/346 |
| Idle thumbnail | 4/6 | 4/7 | 16/20 | 5/8 | 5/8 |
| Idle 1920×1440 viewer | 24/32 | 19/31 | 35/39 | 26/31 | 26/31 |
| Six-operation mix | 1/27 | 1/26 | 17/41 | 1/25 | 1/30 |
| Burst of 24, one conversation (queue) | 21/38 (36) | 28/43 (42) | 183/338 (323) | 32/48 (46) | 26/45 (43) |
| Burst of 48, one conversation (queue) | 33/63 (62) | 45/73 (72) | 348/658 (648) | 46/75 (69) | 45/70 (69) |
| Burst of 96, one conversation (queue) | 64/112 (111) | 72/119 (118) | 675/1290 (1282) | 65/134 (133) | 69/132 (131) |
| Burst of 48 across 6 conversations (queue) | 42/79 (78) | 42/67 (66) | 351/656 (642) | 83/143 (140) | 681/1598 (1597) |
| Cold chat list, 24 profiles + 8 groups (max) | 33/55, max 59 | 36/56, max 58 | 235/441, max 460 | 108/196, max 201 | 970/2206, max 2521 |
| Scroll, 8 tiles every 400 ms | 11/20 | 11/22 | 67/115 | 13/23 | 12/22 |
| No result or admission timeout | 0 | 0 | 0 | 0 | 0 |
| Live image workers, at most | 1 | 1 | 3 | 3 | 3 |
| Caller FDs, at most | 64 | 66 | 66 | 64 | 64 |
| Idle PSS, workers and zygote (MiB) | 48.2 | 56.6 | 72.7 | 73.3 | 91.2 |

| UI level | Master | Reusable + zygote | Per-input + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: |
| Grid passes | 4 | 4 | 4 | 4 | 4 |
| Tiles loaded while on screen, median per pass | 22 | 99 | 99 | 99 | 96 |
| Tiles failed / visible tiles left blank | 51 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Tile p50/p95, median of passes | 379/433 | 19/431 | 156/424 | 21/424 | 16/393 |
| Slowest tile (settle window 10 000) | 1050 | 460 | 454 | 460 | 420 |
| Frame p95, median of passes | 17 | 20 | 17 | 20 | 20 |
| Avatar within 2.5 s, idle (p50) | 60 of 60 (10) | 60 of 60 (11) | 60 of 60 (56) | 60 of 60 (10) | 60 of 60 (9) |
| Emoji within 250 ms, idle (p50) | 60 of 60 (4) | 60 of 60 (5) | 60 of 60 (18) | 60 of 60 (5) | 44 of 60 (5) |
| Avatar within 2.5 s, behind a burst in another conversation (p50) | 60 of 60 (39) | 60 of 60 (48) | 60 of 60 (409) | 60 of 60 (14) | 60 of 60 (14) |
| Emoji within 250 ms, behind a burst in another conversation (p50) | 60 of 60 (31) | 60 of 60 (38) | 0 of 60 (None) | 60 of 60 (6) | 60 of 60 (6) |
| Avatar within 2.5 s, behind a burst in the same conversation (p50) | 60 of 60 (42) | 60 of 60 (44) | 60 of 60 (401) | 60 of 60 (13) | 60 of 60 (12) |
| Emoji within 250 ms, behind a burst in the same conversation (p50) | 60 of 60 (32) | 60 of 60 (36) | 1 of 60 (11) | 60 of 60 (36) | 60 of 60 (35) |
| Viewer cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 386/412 (1 of 10) | 0; 92/97 (1 of 10) | 0; 56/76 (10 of 10) | 0; 74/84 (10 of 10) | 0; 378/429 (10 of 10) |
| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 18/19 (10 of 10) | 0; 18/21 (10 of 10) | 0; 31/32 (10 of 10) | 0; 17/22 (10 of 10) | 0; 18/22 (10 of 10) |

#### API 36

| Throughput, p50/p95 ms | Master | Reusable + zygote | Per-input + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: |
| First image after launch | 227/283 | 250/326 | 279/321 | 266/329 | 244/266 |
| First image, new lane | 225/243 | 33/45 | 40/74 | 32/41 | 234/254 |
| First image, new domain on a warm lane | 4/9 | 5/7 | 14/20 | 42/53 | 262/307 |
| Idle thumbnail | 4/7 | 5/8 | 15/20 | 5/9 | 4/7 |
| Idle 1920×1440 viewer | 21/46 | 24/30 | 29/38 | 23/31 | 18/24 |
| Six-operation mix | 1/26 | 1/26 | 20/43 | 1/31 | 1/24 |
| Burst of 24, one conversation (queue) | 21/41 (39) | 28/48 (47) | 218/404 (379) | 31/50 (49) | 31/46 (45) |
| Burst of 48, one conversation (queue) | 35/65 (64) | 50/82 (81) | 455/817 (810) | 54/84 (82) | 51/81 (80) |
| Burst of 96, one conversation (queue) | 70/124 (122) | 78/141 (139) | 893/1706 (1676) | 75/141 (140) | 67/134 (133) |
| Burst of 48 across 6 conversations (queue) | 39/63 (62) | 42/75 (74) | 428/805 (779) | 84/175 (173) | 723/1438 (1436) |
| Cold chat list, 24 profiles + 8 groups (max) | 36/60, max 63 | 32/62, max 67 | 281/559, max 613 | 99/221, max 278 | 945/1666, max 2003 |
| Scroll, 8 tiles every 400 ms | 11/22 | 14/25 | 79/143 | 12/21 | 12/24 |
| No result or admission timeout | 0 | 0 | 0 | 0 | 0 |
| Live image workers, at most | 1 | 1 | 3 | 3 | 3 |
| Caller FDs, at most | 104 | 102 | 104 | 104 | 102 |
| Idle PSS, workers and zygote (MiB) | 60.0 | 64.9 | 73.4 | 82.6 | 105.4 |

| UI level | Master | Reusable + zygote | Per-input + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: |
| Grid passes | 4 | 4 | 4 | 4 | 4 |
| Tiles loaded while on screen, median per pass | 84 | 111 | 111 | 114 | 111 |
| Tiles failed / visible tiles left blank | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Tile p50/p95, median of passes | 13/375 | 9/303 | 159/500 | 8/256 | 10/276 |
| Slowest tile (settle window 10 000) | 1033 | 325 | 625 | 273 | 305 |
| Frame p95, median of passes | 43 | 34 | 43 | 36 | 37 |
| Avatar within 2.5 s, idle (p50) | 60 of 60 (10) | 60 of 60 (13) | 60 of 60 (69) | 60 of 60 (11) | 60 of 60 (9) |
| Emoji within 250 ms, idle (p50) | 60 of 60 (5) | 60 of 60 (6) | 60 of 60 (18) | 60 of 60 (6) | 42 of 60 (5) |
| Avatar within 2.5 s, behind a burst in another conversation (p50) | 60 of 60 (39) | 60 of 60 (56) | 60 of 60 (533) | 60 of 60 (16) | 60 of 60 (14) |
| Emoji within 250 ms, behind a burst in another conversation (p50) | 60 of 60 (30) | 60 of 60 (43) | 1 of 60 (16) | 60 of 60 (7) | 60 of 60 (6) |
| Avatar within 2.5 s, behind a burst in the same conversation (p50) | 60 of 60 (43) | 60 of 60 (52) | 60 of 60 (552) | 60 of 60 (14) | 60 of 60 (13) |
| Emoji within 250 ms, behind a burst in the same conversation (p50) | 60 of 60 (34) | 60 of 60 (42) | 0 of 60 (None) | 60 of 60 (39) | 60 of 60 (36) |
| Viewer cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 329/379 (0 of 10) | 0; 99/122 (0 of 10) | 0; 49/69 (10 of 10) | 0; 77/89 (10 of 10) | 0; 315/346 (10 of 10) |
| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 17/18 (10 of 10) | 0; 18/20 (10 of 10) | 0; 41/55 (10 of 10) | 0; 17/18 (10 of 10) | 0; 17/24 (10 of 10) |

UI times are milliseconds; each notification row counts 60 notifications in ten
rounds. "Other" puts the emoji in another conversation than the burst, "same" in
the burst's own conversation; the avatar is always a published profile picture.

| Proposed budget item | API 30 domain + zygote | API 30 domain, no zygote | API 36 domain + zygote | API 36 domain, no zygote |
| --- | --- | --- | --- | --- |
| T1 No failed request or admission timeout | Met, 0 | Met, 0 | Met, 0 | Met, 0 |
| T2 Idle thumbnail p95 within 50 ms of master | Met, +2 | Met, +2 | Met, +2 | Met, +0 |
| T3 Idle viewer p95 within 100 ms of master | Met, -1 | Met, -1 | Met, -15 | Met, -22 |
| T4 First image after launch p95 within 150 ms of master | Met, -40 | Met, -75 | Met, +46 | Met, -17 |
| T5 First image in a new domain p95 within 150 ms of master's new lane | Met, 56 vs 327 | Met, 346 vs 327 | Met, 53 vs 243 | Met, 307 vs 243 |
| T6 Bursts in one conversation p95 within 0.6, 1.2, 2.5 s | Met, 48/75/134 | Met, 45/70/132 | Met, 50/84/141 | Met, 46/81/134 |
| T7 Burst across conversations p95 within 1.2 s | Met, 143 | **Not met**, 1598 | Met, 175 | **Not met**, 1438 |
| T8 Cold chat list: all within 2.5 s, p95 within 1.2 s | Met, max 201, p95 196 | **Not met**, max 2521, p95 2206 | Met, max 278, p95 221 | **Not met**, max 2003, p95 1666 |
| T9 Scroll rounds p95 within 250 ms | Met, 23 | Met, 22 | Met, 21 | Met, 24 |
| T10 At most three live workers plus the zygote | Met, 3 | Met, 3 | Met, 3 | Met, 3 |
| T11 No caller FD growth | Met, 64 vs 64 | Met, 64 vs 64 | Met, 104 vs 104 | Met, 102 vs 104 |
| T12 Idle PSS growth within 40 MiB | Met, +25.1 | **Not met**, +43.0 | Met, +22.6 | **Not met**, +45.4 |
| U1 Grid: no failed tile, no blank tile | Met, 0/0 | Met, 0/0 | Met, 0/0 | Met, 0/0 |
| U2 Grid frame p95 within one 60 Hz frame of master | Met, +3 | Met, +3 | Met, -7 | Met, -6 |
| U3 Notification avatar within 2.5 s: idle, other, same | Met, 60/60/60 | Met, 60/60/60 | Met, 60/60/60 | Met, 60/60/60 |
| U4 Emoji within 250 ms: idle, other, same | Met, 60/60/60 | **Not met**, 44/60/60 | Met, 60/60/60 | **Not met**, 42/60/60 |
| U5 Cancel delivers nothing; next image within 2 s | Met, max 84 | Met, max 429 | Met, max 89 | Met, max 346 |

On stock Android the domain lane meets every proposed item on both APIs.
Without the application zygote, the lower bound for GrapheneOS, it misses four:

- A burst across six conversations: p95 1.6 s (API 30) and 1.4 s (API 36)
  against 1.2 s.
- The cold chat list: p95 2.2 s and 1.7 s against 1.2 s, slowest 2.5 s and
  2.0 s.
- Idle memory: +43 and +45 MiB against 40, as every worker carries its own
  heap without the shared zygote.
- Idle emoji artwork: 44 and 42 of 60 within 250 ms, the misses being an
  emoji whose conversation has no live worker yet.

Each new domain there costs a full process start, about 0.25 to 0.35 s. Exec
spawning costs more again, so on a GrapheneOS device these misses are expected
to be at least as large; that remains unmeasured. The options are to accept
them, to raise the live limit and spare count where workers are exec-spawned
(more parallel starts for more memory), to keep the reusable lane there, or to
measure on a GrapheneOS device first.

#### Round 4 re-measure

Round 3's review led to four fixes:
- the session's refusal ledger is locked across domain lanes;
- a conversation's worker sees only member-pinned bytes;
- picks to send are keyed by conversation;
- undeclared avatar decodes are single-use.

The rows these can move were measured again on the same emulators, against the
same budget, unchanged. Columns: master (the reusable worker without the
application zygote), domain + zygote and domain without the zygote, each in
ABBA order; host load 3 to 10.

Two scenarios are new. *Cold chat list, 8 groups' URL pictures single-use* is
the cold chat list with every group showing a URL picture. *Group URL picture*
repeats the notification artwork with a group's URL picture as the avatar. Both
decode single-use under the new rule. Existing scenario code is unchanged, so
their rows stay comparable with run 4.

##### API 30

| Throughput, p50/p95 ms | Master | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: |
| First image after launch | 335/438 | 454/874 | 331/416 |
| First image, new lane | 323/345 | 32/71 | 339/376 |
| First image, new domain on a warm lane | 2/7 | 37/50 | 341/386 |
| Idle thumbnail | 2/4 | 3/7 | 2/3 |
| Idle 1920×1440 viewer | 15/19 | 25/37 | 14/27 |
| Six-operation mix | 1/28 | 1/27 | 1/28 |
| Burst of 24, one conversation (queue) | 16/28 (27) | 23/38 (36) | 27/44 (43) |
| Burst of 48, one conversation (queue) | 34/86 (84) | 42/68 (67) | 48/87 (86) |
| Burst of 96, one conversation (queue) | 56/105 (103) | 63/125 (124) | 67/135 (133) |
| Burst of 48 across 6 conversations (queue) | 37/73 (71) | 85/169 (168) | 690/1667 (1665) |
| Cold chat list, 24 profiles + 8 groups (max) | 27/57, max 61 | 42/234, max 321 | 1350/3076, max 3080 |
| Scroll, 8 tiles every 400 ms | 7/12 | 11/20 | 9/19 |
| Cold chat list, 8 groups' URL pictures single-use (max) | 24/57, max 63 | 51/203, max 263 | 973/2270, max 2636 |
| No result or admission timeout | 0 | 0 | 0 |
| Live image workers, at most | 1 | 3 | 3 |
| Caller FDs, at most | 66 | 64 | 66 |
| Idle PSS, workers and zygote (MiB) | 47.7 | 71.4 | 90.6 |

| UI level | Master | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: |
| Grid passes | 4 | 4 | 4 |
| Tiles loaded while on screen, median per pass | 21 | 99 | 108 |
| Tiles failed / visible tiles left blank | 22 / 0 | 0 / 0 | 0 / 0 |
| Tile p50/p95, median of passes | 999/1063 | 22/465 | 22/378 |
| Slowest tile (settle window 10 000) | 1089 | 671 | 393 |
| Frame p95, median of passes | 23 | 44 | 20 |
| Avatar within 2.5 s, idle (p50) | 60 of 60 (5) | 60 of 60 (9) | 60 of 60 (5) |
| Emoji within 250 ms, idle (p50) | 60 of 60 (2) | 60 of 60 (5) | 45 of 60 (3) |
| Avatar within 2.5 s, behind a burst in another conversation (p50) | 60 of 60 (35) | 60 of 60 (14) | 60 of 60 (14) |
| Emoji within 250 ms, behind a burst in another conversation (p50) | 60 of 60 (25) | 60 of 60 (6) | 60 of 60 (6) |
| Avatar within 2.5 s, behind a burst in the same conversation (p50) | 60 of 60 (41) | 60 of 60 (12) | 60 of 60 (10) |
| Emoji within 250 ms, behind a burst in the same conversation (p50) | 60 of 60 (31) | 60 of 60 (33) | 60 of 60 (26) |
| Group URL picture within 2.5 s, single-use, idle (p50) | 60 of 60 (6) | 60 of 60 (71) | 59 of 60 (685) |
| Group URL picture within 2.5 s, single-use, behind a burst in another conversation (p50) | 60 of 60 (40) | 60 of 60 (168) | 31 of 60 (1037) |
| Group URL picture within 2.5 s, single-use, behind a burst in the same conversation (p50) | 60 of 60 (38) | 60 of 60 (192) | 22 of 60 (1471) |
| Viewer cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 456/701 (0 of 10) | 0; 74/91 (10 of 10) | 0; 397/494 (10 of 10) |
| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 21/30 (10 of 10) | 0; 18/20 (10 of 10) | 0; 20/33 (10 of 10) |

##### API 36

| Throughput, p50/p95 ms | Master | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: |
| First image after launch | 238/346 | 274/358 | 258/297 |
| First image, new lane | 248/315 | 37/55 | 251/293 |
| First image, new domain on a warm lane | 3/8 | 44/67 | 271/292 |
| Idle thumbnail | 3/7 | 3/7 | 3/6 |
| Idle 1920×1440 viewer | 21/30 | 21/37 | 16/25 |
| Six-operation mix | 1/25 | 1/28 | 1/26 |
| Burst of 24, one conversation (queue) | 28/47 (46) | 34/52 (50) | 23/41 (40) |
| Burst of 48, one conversation (queue) | 42/74 (73) | 45/76 (75) | 40/64 (63) |
| Burst of 96, one conversation (queue) | 66/129 (128) | 81/146 (145) | 71/149 (148) |
| Burst of 48 across 6 conversations (queue) | 42/70 (69) | 107/248 (245) | 588/1331 (1328) |
| Cold chat list, 24 profiles + 8 groups (max) | 27/50, max 53 | 117/263, max 341 | 291/1767, max 2019 |
| Scroll, 8 tiles every 400 ms | 9/17 | 11/21 | 11/22 |
| Cold chat list, 8 groups' URL pictures single-use (max) | 32/74, max 79 | 44/286, max 362 | 534/1820, max 2128 |
| No result or admission timeout | 0 | 0 | 0 |
| Live image workers, at most | 1 | 3 | 3 |
| Caller FDs, at most | 104 | 102 | 104 |
| Idle PSS, workers and zygote (MiB) | 60.0 | 82.1 | 105.6 |

| UI level | Master | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: |
| Grid passes | 4 | 4 | 4 |
| Tiles loaded while on screen, median per pass | 79 | 112 | 111 |
| Tiles failed / visible tiles left blank | 48 / 0 | 0 / 0 | 0 / 0 |
| Tile p50/p95, median of passes | 8/290 | 10/285 | 10/314 |
| Slowest tile (settle window 10 000) | 1051 | 364 | 403 |
| Frame p95, median of passes | 50 | 52 | 47 |
| Avatar within 2.5 s, idle (p50) | 60 of 60 (9) | 60 of 60 (8) | 60 of 60 (7) |
| Emoji within 250 ms, idle (p50) | 60 of 60 (4) | 60 of 60 (5) | 43 of 60 (4) |
| Avatar within 2.5 s, behind a burst in another conversation (p50) | 60 of 60 (46) | 60 of 60 (16) | 60 of 60 (15) |
| Emoji within 250 ms, behind a burst in another conversation (p50) | 60 of 60 (34) | 60 of 60 (7) | 60 of 60 (8) |
| Avatar within 2.5 s, behind a burst in the same conversation (p50) | 60 of 60 (38) | 60 of 60 (15) | 60 of 60 (12) |
| Emoji within 250 ms, behind a burst in the same conversation (p50) | 60 of 60 (29) | 60 of 60 (44) | 60 of 60 (35) |
| Group URL picture within 2.5 s, single-use, idle (p50) | 60 of 60 (12) | 60 of 60 (88) | 60 of 60 (551) |
| Group URL picture within 2.5 s, single-use, behind a burst in another conversation (p50) | 60 of 60 (48) | 60 of 60 (249) | 38 of 60 (1308) |
| Group URL picture within 2.5 s, single-use, behind a burst in the same conversation (p50) | 60 of 60 (44) | 60 of 60 (222) | 35 of 60 (1054) |
| Viewer cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 331/420 (0 of 10) | 0; 89/120 (10 of 10) | 0; 313/778 (10 of 10) |
| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 18/20 (10 of 10) | 0; 20/21 (10 of 10) | 0; 18/56 (10 of 10) |

| Proposed budget item | API 30 domain + zygote | API 30 repeat | API 30 domain, no zygote | API 36 domain + zygote | API 36 domain, no zygote |
| --- | --- | --- | --- | --- | --- |
| T1 No failed request or admission timeout | Met, 0 | Met, 0 | Met, 0 | Met, 0 | Met, 0 |
| T2 Idle thumbnail p95 within 50 ms of master | Met, +3 | Met, +1 | Met, -1 | Met, +0 | Met, -1 |
| T3 Idle viewer p95 within 100 ms of master | Met, +18 | Met, +9 | Met, +8 | Met, +7 | Met, -5 |
| T4 First image after launch p95 within 150 ms of master | **Not met**, +436 | **Not met**, +255 | Met, -22 | Met, +12 | Met, -49 |
| T5 First image in a new domain p95 within 150 ms of master's new lane | Met, 50 vs 345 | Met, 54 vs 345 | Met, 386 vs 345 | Met, 67 vs 315 | Met, 292 vs 315 |
| T6 Bursts in one conversation p95 within 0.6, 1.2, 2.5 s | Met, 38/68/125 | Met, 38/71/124 | Met, 44/87/135 | Met, 52/76/146 | Met, 41/64/149 |
| T7 Burst across conversations p95 within 1.2 s | Met, 169 | Met, 143 | **Not met**, 1667 | Met, 248 | **Not met**, 1331 |
| T8 Cold chat list: all within 2.5 s, p95 within 1.2 s | Met, max 321, p95 234 | Met, max 214, p95 194 | **Not met**, max 3080, p95 3076 | Met, max 341, p95 263 | **Not met**, max 2019, p95 1767 |
| T8 The same with group URL pictures single-use | Met, max 263, p95 203 | Met, max 256, p95 201 | **Not met**, max 2636, p95 2270 | Met, max 362, p95 286 | **Not met**, max 2128, p95 1820 |
| T9 Scroll rounds p95 within 250 ms | Met, 20 | Met, 20 | Met, 19 | Met, 21 | Met, 22 |
| T10 At most three live workers plus the zygote | Met, 3 | Met, 3 | Met, 3 | Met, 3 | Met, 3 |
| T11 No caller FD growth | Met, 64 vs 66 | Met, 64 vs 66 | Met, 66 vs 66 | Met, 102 vs 104 | Met, 104 vs 104 |
| T12 Idle PSS growth within 40 MiB | Met, +23.7 | not re-run | **Not met**, +42.9 | Met, +22.1 | **Not met**, +45.6 |
| U1 Grid: no failed tile, no blank tile | Met, 0/0 | Met, 0/0 | Met, 0/0 | Met, 0/0 | Met, 0/0 |
| U2 Grid frame p95 within one 60 Hz frame of master | **Not met**, +21 | Met, -3 | Met, -3 | Met, +2 | Met, -3 |
| U3 Notification avatar within 2.5 s: idle, other, same | Met, 60/60/60 | Met, 60/60/60 | Met, 60/60/60 | Met, 60/60/60 | Met, 60/60/60 |
| U3 The same with a group URL picture single-use | Met, 60/60/60 | Met, 60/60/60 | **Not met**, 59/31/22 | Met, 60/60/60 | **Not met**, 60/38/35 |
| U4 Emoji within 250 ms: idle, other, same | Met, 60/60/60 | Met, 60/60/60 | **Not met**, 45/60/60 | Met, 60/60/60 | **Not met**, 43/60/60 |
| U5 Cancel delivers nothing; next image within 2 s | Met, max 91 | Met, max 221 | Met, max 494 | Met, max 120 | Met, max 778 |

The first API 30 run on the candidate began at host load 10.6, straight after a
local build. Domain + zygote then missed T4 (cold launch p95 874 ms, samples 407
to 874) and U2 (frame p95 44 ms). A repeat of that phase was decided before it
ran and is shown beside it, not instead of it: U2 met, T4 missed again at p95
693 ms. A diagnostic A/B on the same emulator ran the round-3 candidate APK
against this one in ABBA order, domain lane only. It gave the same cold launch,
p50 400 to 410 ms; cold-launch p95 was 432 (round 3), 462 (round 4), 429
(round 4) and 412 ms (round 3). So the T4 misses are not caused by this round's
code; the campaign tails (693 to 874 ms) are unexplained, and they are recorded
as measured. T4's "p95" over ten samples is effectively the maximum, which
decides T4 both ways: master's 438 ms is one sample, and its other nine were
320 to 356 ms.

What changed from run 4:
- On stock Android the domain lane still meets every other item on both APIs.
  A chat list whose eight groups show URL pictures single-use stays within
  T8: p95 203 ms (API 30), 286 ms (API 36).
- A notification with a group's URL picture meets U3 on stock Android at p50
  71 to 249 ms.
- Without the application zygote it now misses U3 as well, besides T7, T8,
  T12 and U4. Behind a burst, only 31 and 22 of 60 group pictures arrived
  within 2.5 s on API 30, and 38 and 35 on API 36.
- The cause is that single-use inputs are admitted one at a time: each
  waits for the previous single-use worker's death. Ten such pictures cost ten
  process starts in series, about 0.35 s each without a zygote.
- On GrapheneOS that path is at least as slow. The options there are to admit
  several fresh single-use workers at once, within the live limit, or to
  accept that a group URL picture can miss its notification when ten arrive
  at once.

#### Round 5: editor renders single-use

Round 4's review moved every edited render and the editor's own preview to
single-use workers, so a worker an earlier pick compromised cannot restore what
a crop, blur or mark removed. The one measured row this moves is U5's editor
half, whose harness decodes through the editor preview. It was measured again on
the domain lane, recorded beforehand, against the same budget:

| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: |
| API 30 | 0; 67/79 (10 of 10) | 0; 69/772 (10 of 10) |
| API 36 | 0; 79/93 (10 of 10) | 0; 527/738 (10 of 10) |

U5 stays met on both builds and both APIs. Containment acceptance passed 34 of
34 on both APIs with the same build.

Against keeping the reusable default, which ships the same launch path
(*Reusable + zygote*), the domain lane costs a first image in a new
conversation of 42 to 47 ms at p50 against 5, a burst across conversations of
p95 143 to 175 ms against 67 to 75, a cold chat list of p95 196 to 221 ms
against 56 to 62, three live workers against one, and 17 MiB of idle memory. The
single-conversation rows, notification artwork and cancellation are within
noise of it, and after a cancelled viewer decode the next image arrives at the
first attempt, where the reusable lane needed a retry in 19 of 20 rounds.
Against the per-input lane it replaces, emoji artwork behind a burst arrives in
time (60 of 60 against 0 or 1), and bursts within a conversation are about ten
times faster.

The grid's slowest tile on the domain lane was 460 ms (API 30) and 273 ms
(API 36) over four passes each, against the 10-second settle window, so the
window keeps a margin of more than 9.5 seconds and stays as it is. The 10.8 and
11.2-second tiles seen in earlier campaigns occurred only on the per-input lane
under host load (frame p50 32 to 47 ms), where every tile queues on one
serialized lane and each request starts a process. The master build's grid
failed 51 tiles over its API 30 passes: its reusable worker restarts cold for
every tile scrolled away mid-decode.

Rejected alternatives, on the same emulators:

- Keeping the reusable worker separates no inputs; it is the comparison and the
  lane to restore.
- Per-input generations, this change's earlier proposal: emoji artwork misses
  its bound behind any burst, bursts are about ten times slower, and on
  GrapheneOS every input would pay a full process start.
- One worker for every group image or for the chat list: a group's image would
  reach a decoder that another group's image may have compromised.
- A bound on admitted requests across domains, tried in a stopped run: a freed
  permit went to a domain without a worker, which evicted the one that had just
  finished (mixed-burst p50/p95 821/2257 ms on API 30). Claiming the worker
  before creating descriptors bounds them instead.
- In earlier runs on the same emulators, preloading the worker classes cut a
  fresh worker's first decode from about 10 ms to 3 ms. Unbinding together
  with RETIRE raised the death wait from 2.4 ms to 48 ms at p95, so unbinding
  is only an escalation.

### Default

Production installation uses domain-bound generations, switched in #3516
pending approval of the [proposed budget](#measurements-and-proposed-budget).
`ReusableMediaTransport` is no longer installed. It stays in production code as
the lane to restore if the budget is rejected and as the measurement baseline.
Removing it waits for that decision and for #3439, which edits the facade's
animation type checks.

### Residual risks

- A compromised worker keeps whatever it can read of its own domain until the
  generation is replaced: up to 64 inputs or 30 seconds idle.
- Callers name domains. A caller that names the wrong one decides what a worker
  can see. A caller that names none gets Unique, never a shared domain: the
  avatar loader and the generic picture rows fall back to it too, and
  `PictureDomainCoverageTest` fails when a production loader call or a picture
  row with a URL names no domain, or when production code passes a decoding
  loader function by reference. The loader's fallback returns a domain an
  enclosing `MediaTrustScope` declared before Unique, so an undeclared call
  inside a declared scope inherits it; the guard reads call sites, not scopes,
  and instead requires every production call to name its domain, which leaves
  that inheritance to test and harness code. Whether a named domain is the right
  one rests on review and on the audit above.
- The avatar cache and its in-flight requests are keyed by URL, not domain. If
  one URL were both a person's published picture and a group's URL picture, the
  first request's domain would decode it for both.
- Within the outgoing domain a hostile earlier pick can carry another pick of
  the same conversation, including one removed from the draft, into a sent
  image. Edited (cropped, blurred or marked) renders are decoded single-use, so
  redactions cannot be undone by an earlier pick. In the local domain a hostile
  emoji file can draw a private contact picture in an emoji slot on this device
  or in this user's own notification; nothing from the local domain is sent.
- A cooperative self-exit ends the worker process only. Android's escalation
  kill covers the process group. A forked descendant of a compromised worker is
  outside the Binder witness.
- One idle spare stays resident while a slot is free, plus the application
  zygote on stock Android.
- On stock Android, forks share the application zygote's layout for preloaded
  code, so one generation's address-space disclosure applies to later ones.
- Heap tagging through the application-zygote launch path is unqualified on
  MTE hardware; see [native heap memory tagging](native-heap-memory-tagging.md).
- On GrapheneOS, every new domain and every Unique input pays an exec-spawned
  process start; see the [proposed budget](#measurements-and-proposed-budget)
  for the lower bound measured without the application zygote.
- Unique inputs are admitted one at a time, each after the previous single-use
  worker's death. Without the application zygote, ten notifications with group
  URL pictures behind a burst missed the 2.5 s artwork bound for a third to
  two thirds of them; see the [round 4 re-measure](#round-4-re-measure).
- There is no visible-first priority within a domain. Callers cancelled through
  interruption, such as the image bubble decode, leave the queue; other callers
  wait within the eight-second admission bound.

The test-only [still-worker baseline](media-worker-baseline.md) records the
historical reuse characterization and the matched throughput harness. The
[still-image worker lifetime](media-worker-lifetime.md) page records the owner,
deadline and test boundaries.

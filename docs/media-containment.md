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
| Outgoing | account, conversation, pick | One file the user picked from the device to send into one conversation, for its whole life in the draft: its composer and shelf previews, staging (inspection, EXIF and the Original or standard render), the photo editor's inspection, preview and edited render, its thumbhash and its just-sent thumbnail. Gallery files come from many authors, so each pick gets its own worker. The pick is the composer slot's id, which a restored draft attachment keeps as its own, or a document's URI. Removing a pick, or its last decode after sending, retires its worker. |
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
| Each pick to send, every decode of it included | That file's author | Outgoing, one domain per pick (was one per conversation, and before that account-wide Local) |
| Edited renders and the editor preview | That pick's own file | The pick's own outgoing domain (round 5 made them single-use; one worker per pick protects every other pick's redactions without it) |

Within its domain a compromised worker can still read every later input the
generation serves, including descriptors it retained; alter the pixels it
returns, which the parent validates for shape but not content; and use CPU and
memory up to its watchdog. An outgoing worker only ever sees one pick: a
hostile file can see no other pick, removed from the draft or not, so it can
neither carry one into a sent image nor undo a crop, blur or mark of another
pick. It can return wrong pixels for its own pick, including an edit of that
same file with its crop, blur or mark undone, since any worker that decodes it
is compromised alike; that content is its author's own. In the local domain a hostile emoji file can draw a private
contact picture in an emoji slot, on this device or in this user's own
notification. Escapes through the kernel or system services are not contained
by domains.

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
- Account reset and close retire every generation and spare. Removing a pick
  from the draft retires that pick's generation: at once when it is idle, after
  its request in flight otherwise. A sent document's generation retires at once;
  a sent photo's after its just-sent poster, its last decode, or after 30 seconds
  idle when it gets none.
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
  death frees the slot; a busy domain is never evicted. As many evictions run
  at once as requests wait for a slot, so an album of new picks is not served
  one eviction at a time, and none beyond that: slots already being freed are
  awaited while they cover every waiting request.
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
a crop, blur or mark removed. Round 6 replaced this with one worker per pick
(below), which protects every other pick's redactions in every decode, not only
the editor's. The one measured row this moves is U5's editor
half, whose harness decodes through the editor preview. It was measured again on
the domain lane, recorded beforehand, against the same budget:

| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: |
| API 30 | 0; 67/79 (10 of 10) | 0; 69/772 (10 of 10) |
| API 36 | 0; 79/93 (10 of 10) | 0; 527/738 (10 of 10) |

U5 stays met on both builds and both APIs. Containment acceptance passed 34 of
34 on both APIs with the same build.

#### Round 6: one worker per pick

Round 5's review found that one outgoing worker per conversation let a hostile
pick read every later pick of that conversation, originals included, before any
crop, blur or mark: round 5's single-use editor renders protected only the
editor's own decodes. Each pick now has its own outgoing domain, keyed by
account, conversation and pick, and every decode of that pick runs there: the
composer and shelf previews, staging (inspection, EXIF and the Original or
standard render), the editor's inspection, preview and edited render, the
thumbhash and the just-sent thumbnail. No decode of one pick reaches another
pick's worker.

Edited renders and the editor preview go back to the pick's own domain rather
than single-use. Their input is that pick's own source. A hostile source
compromises whichever worker decodes it, a fresh one included, so it can undo a
crop, blur or mark of itself either way; what one worker per pick adds is that
no other pick's source ever reaches that worker. A single-use worker would
protect nothing more and would cost a process start per edit, about 0.3 s each
without the application zygote.

Removing a pick retires its worker at once when it is idle, after its request
otherwise, behind the same death fence as every retirement. A sent document
retires at acceptance. A sent photo retires after its just-sent poster, or after
30 seconds idle without one: retiring it at acceptance made its pending preview
and its poster each start a fresh worker for the same pick, two process starts
per photo where one suffices (step 4 of the order of work below).

Admission changed in one way. When every slot is taken, as many idle domains
are evicted at once as claims wait for a slot, so ten new picks start up to
three at a time instead of one eviction at a time.
Single-use admission is unchanged: a Unique input still waits for the previous
single-use worker's death. Admitting several at once would change the tested
round-1 contract that a single-use input never overlaps the previous one's
lifetime, and it would no longer serve picks, which have left the Unique lane;
it stays a GrapheneOS option for group URL pictures (round 4).

##### Budget amendment and order of work

`BUDGET-v3-amend-picks` (sha256 `ed72eb80bf461ba1…`) was recorded at 10:06:37
on 30 October 2026, before the album harness existed and before any round-6
run; `BUDGET-v3` is unchanged. It adds three items for the pick lane, judged on
stock Android (application zygote) and on the proxy without it:

- A1, album preparation: ten picked 4032×3024 photos, all prepared at once (read,
  inspect and render as staging does, plus the 256 px shelf preview); p95 within
  master's p95 + 1.0 s and every album within 10 s.
- A2, album send: the same ten photos read and encoded one after another as an
  unprepared slot is at send time, then each just-sent poster; the same limits.
- A3: no BUDGET-v3 item that domain + zygote met in round 4 newly misses on
  stock Android, and no item the proxy met in round 4 newly misses there.

"Master" is the master lane on the no-zygote build, as in BUDGET-v3. Each
build's own reusable lane is shown beside it.

Order of work, from the campaign's ordering log:

1. 10:06:37: amendment recorded.
2. 10:16:02: run 1 started on builds of `2463aa3c1`. It was stopped at 10:17:16,
   before any result was read, because it started at host load 9.2; its output
   is unused.
3. Run 2: the same builds, started once the load was below 4, finished at
   11:05:42. Its first API 36 zygote throughput run failed straight after
   install ("Image workers or the app zygote stayed alive"); it was re-run once,
   unchanged, after a force-stop, and the failed output is kept. Master-lane
   album runs reported a harness teardown failure after emitting complete
   rows, a double close that `37d5befcf` fixes; the rows are used.
4. 11:15:16: run 2's no-zygote send missed A2 by about 6 s, two process starts
   per photo. `d29121c69` retires a sent photo after its poster instead.
   Album rows only were re-run on builds of `37d5befcf`. No other measured
   path changed, so run 2's other rows stand for the final code.
5. 11:27:52: after reading the re-run, a further API 30 album pass was declared
   before it ran, each lane starting below load 4. The re-run's load log showed
   10.2 at the end of its stock API 30 block, against 2.7 to 4.1 at every other
   lane start. All three album runs are shown; none replaces another.

##### Run 2: throughput and UI rows

Same emulators, harness and ABBA lane order as round 4, host load 1.2 to 4.1
at every lane start. *Reusable + zygote* is the reusable worker on this change's
build; the other columns are as in run 4.

###### API 30

| Throughput, p50/p95 ms | Master | Reusable + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: |
| First image after launch | 316/355 | 427/534 | 403/468 | 326/356 |
| First image, new lane | 318/356 | 27/37 | 29/61 | 327/346 |
| First image, new domain on a warm lane | 5/6 | 3/7 | 41/90 | 332/360 |
| Idle thumbnail | 4/6 | 3/5 | 3/6 | 4/6 |
| Idle 1920×1440 viewer | 27/32 | 24/34 | 27/42 | 21/34 |
| Six-operation mix | 1/25 | 1/31 | 1/27 | 1/26 |
| Burst of 24, one conversation (queue) | 30/45 (43) | 25/43 (42) | 29/46 (44) | 32/48 (47) |
| Burst of 48, one conversation (queue) | 43/71 (70) | 39/65 (64) | 42/69 (68) | 41/67 (66) |
| Burst of 96, one conversation (queue) | 68/115 (114) | 68/114 (113) | 66/122 (120) | 68/133 (132) |
| Burst of 48 across 6 conversations (queue) | 48/73 (71) | 45/76 (75) | 73/144 (143) | 660/1634 (1633) |
| Cold chat list, 24 profiles + 8 groups (max) | 33/56, max 59 | 34/52, max 54 | 44/200, max 249 | 693/2177, max 2526 |
| Scroll, 8 tiles every 400 ms | 12/22 | 12/20 | 11/19 | 12/21 |
| Cold chat list, 8 groups' URL pictures single-use (max) | 37/53, max 57 | 31/48, max 51 | 52/227, max 287 | 651/2241, max 2608 |
| No result or admission timeout | 0 | 0 | 0 | 0 |
| Live image workers, at most | 1 | 1 | 3 | 3 |
| Caller FDs, at most | 65 | 66 | 64 | 66 |
| Idle PSS, workers and zygote (MiB) | 47.6 | 55.6 | 71.6 | 90.0 |

| UI level | Master | Reusable + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: |
| Grid passes | 4 | 4 | 4 | 4 |
| Tiles loaded while on screen, median per pass | 26 | 99 | 99 | 86 |
| Tiles failed / visible tiles left blank | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Tile p50/p95, median of passes | 577/729 | 12/440 | 12/422 | 16/395 |
| Slowest tile (settle window 10 000) | 1051 | 464 | 468 | 417 |
| Frame p95, median of passes | 16 | 21 | 22 | 21 |
| Avatar within 2.5 s, idle (p50) | 60 of 60 (11) | 60 of 60 (9) | 60 of 60 (7) | 60 of 60 (8) |
| Emoji within 250 ms, idle (p50) | 60 of 60 (5) | 60 of 60 (4) | 60 of 60 (4) | 52 of 60 (5) |
| Avatar within 2.5 s, behind a burst in another conversation (p50) | 60 of 60 (44) | 60 of 60 (42) | 60 of 60 (13) | 60 of 60 (14) |
| Emoji within 250 ms, behind a burst in another conversation (p50) | 60 of 60 (34) | 60 of 60 (31) | 60 of 60 (6) | 60 of 60 (6) |
| Avatar within 2.5 s, behind a burst in the same conversation (p50) | 60 of 60 (45) | 60 of 60 (37) | 60 of 60 (9) | 60 of 60 (13) |
| Emoji within 250 ms, behind a burst in the same conversation (p50) | 60 of 60 (36) | 60 of 60 (27) | 60 of 60 (22) | 60 of 60 (35) |
| Group URL picture within 2.5 s, single-use, idle (p50) | 60 of 60 (12) | 60 of 60 (7) | 60 of 60 (68) | 60 of 60 (659) |
| Group URL picture within 2.5 s, single-use, behind a burst in another conversation (p50) | 60 of 60 (44) | 60 of 60 (44) | 60 of 60 (169) | 32 of 60 (1282) |
| Group URL picture within 2.5 s, single-use, behind a burst in the same conversation (p50) | 60 of 60 (42) | 60 of 60 (40) | 60 of 60 (155) | 30 of 60 (1550) |
| Viewer cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 388/417 (0 of 10) | 0; 99/118 (0 of 10) | 0; 84/143 (10 of 10) | 0; 362/376 (10 of 10) |
| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 17/19 (10 of 10) | 0; 18/20 (10 of 10) | 0; 48/56 (10 of 10) | 0; 332/344 (10 of 10) |

###### API 36

| Throughput, p50/p95 ms | Master | Reusable + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: |
| First image after launch | 226/302 | 309/538 | 247/307 | 241/251 |
| First image, new lane | 223/264 | 37/53 | 31/35 | 233/256 |
| First image, new domain on a warm lane | 4/8 | 4/8 | 52/68 | 262/278 |
| Idle thumbnail | 4/6 | 4/7 | 5/8 | 5/7 |
| Idle 1920×1440 viewer | 21/27 | 19/40 | 18/25 | 22/36 |
| Six-operation mix | 1/24 | 1/28 | 1/25 | 1/24 |
| Burst of 24, one conversation (queue) | 28/45 (43) | 28/48 (47) | 28/46 (44) | 21/32 (31) |
| Burst of 48, one conversation (queue) | 41/66 (65) | 44/72 (71) | 47/76 (75) | 43/68 (67) |
| Burst of 96, one conversation (queue) | 65/109 (108) | 70/129 (127) | 78/138 (137) | 65/134 (132) |
| Burst of 48 across 6 conversations (queue) | 41/63 (62) | 50/80 (78) | 112/207 (205) | 519/1202 (1199) |
| Cold chat list, 24 profiles + 8 groups (max) | 35/51, max 53 | 37/61, max 64 | 81/248, max 298 | 44/1625, max 1860 |
| Scroll, 8 tiles every 400 ms | 11/19 | 11/24 | 13/27 | 11/21 |
| Cold chat list, 8 groups' URL pictures single-use (max) | 30/50, max 53 | 34/69, max 75 | 51/243, max 318 | 265/1706, max 1959 |
| No result or admission timeout | 0 | 0 | 0 | 0 |
| Live image workers, at most | 1 | 1 | 3 | 3 |
| Caller FDs, at most | 104 | 126 | 122 | 102 |
| Idle PSS, workers and zygote (MiB) | 60.9 | 65.5 | 83.5 | 107.2 |

| UI level | Master | Reusable + zygote | Domain + zygote | Domain, no zygote |
| --- | ---: | ---: | ---: | ---: |
| Grid passes | 4 | 4 | 4 | 4 |
| Tiles loaded while on screen, median per pass | 74 | 112 | 114 | 112 |
| Tiles failed / visible tiles left blank | 41 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Tile p50/p95, median of passes | 10/291 | 9/289 | 9/261 | 9/296 |
| Slowest tile (settle window 10 000) | 1019 | 298 | 279 | 318 |
| Frame p95, median of passes | 38 | 40 | 35 | 44 |
| Avatar within 2.5 s, idle (p50) | 60 of 60 (12) | 60 of 60 (13) | 60 of 60 (12) | 60 of 60 (7) |
| Emoji within 250 ms, idle (p50) | 60 of 60 (5) | 60 of 60 (6) | 60 of 60 (6) | 51 of 60 (5) |
| Avatar within 2.5 s, behind a burst in another conversation (p50) | 60 of 60 (44) | 60 of 60 (51) | 60 of 60 (16) | 60 of 60 (14) |
| Emoji within 250 ms, behind a burst in another conversation (p50) | 60 of 60 (33) | 60 of 60 (40) | 60 of 60 (8) | 60 of 60 (7) |
| Avatar within 2.5 s, behind a burst in the same conversation (p50) | 60 of 60 (43) | 60 of 60 (49) | 60 of 60 (15) | 60 of 60 (13) |
| Emoji within 250 ms, behind a burst in the same conversation (p50) | 60 of 60 (33) | 60 of 60 (37) | 60 of 60 (44) | 60 of 60 (34) |
| Group URL picture within 2.5 s, single-use, idle (p50) | 60 of 60 (10) | 60 of 60 (14) | 60 of 60 (93) | 60 of 60 (524) |
| Group URL picture within 2.5 s, single-use, behind a burst in another conversation (p50) | 60 of 60 (48) | 60 of 60 (48) | 60 of 60 (213) | 43 of 60 (1059) |
| Group URL picture within 2.5 s, single-use, behind a burst in the same conversation (p50) | 60 of 60 (47) | 60 of 60 (53) | 60 of 60 (203) | 41 of 60 (1009) |
| Viewer cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 335/449 (0 of 10) | 0; 97/132 (0 of 10) | 0; 82/98 (10 of 10) | 0; 332/491 (10 of 10) |
| Editor cancelled mid-decode: pixels delivered; next image p50/max (first try) | 0; 17/18 (10 of 10) | 0; 17/24 (10 of 10) | 0; 51/54 (10 of 10) | 0; 287/364 (10 of 10) |

| BUDGET-v3 item | API 30 domain + zygote | API 30 domain, no zygote | API 36 domain + zygote | API 36 domain, no zygote |
| --- | --- | --- | --- | --- |
| T1 No failed request or admission timeout | Met, 0 | Met, 0 | Met, 0 | Met, 0 |
| T2 Idle thumbnail p95 within 50 ms of master | Met, +0 | Met, +0 | Met, +2 | Met, +1 |
| T3 Idle viewer p95 within 100 ms of master | Met, +10 | Met, +2 | Met, -2 | Met, +9 |
| T4 First image after launch p95 within 150 ms of master | Met, +113 | Met, +1 | Met, +5 | Met, -51 |
| T5 First image in a new domain within 150 ms of master's new lane | Met, 90 vs 356 | Met, 360 vs 356 | Met, 68 vs 264 | Met, 278 vs 264 |
| T6 Bursts in one conversation p95 within 0.6, 1.2, 2.5 s | Met, 46/69/122 | Met, 48/67/133 | Met, 46/76/138 | Met, 32/68/134 |
| T7 Burst across conversations p95 within 1.2 s | Met, 144 | **Not met**, 1634 | Met, 207 | **Not met**, 1202 |
| T8 Cold chat list: all within 2.5 s, p95 within 1.2 s | Met, max 249, p95 200 | **Not met**, max 2526, p95 2177 | Met, max 298, p95 248 | **Not met**, max 1860, p95 1625 |
| T8 The same with group URL pictures single-use | Met, max 287, p95 227 | **Not met**, max 2608, p95 2241 | Met, max 318, p95 243 | **Not met**, max 1959, p95 1706 |
| T9 Scroll rounds p95 within 250 ms | Met, 19 | Met, 21 | Met, 27 | Met, 21 |
| T10 At most three live workers plus the zygote | Met, 3 | Met, 3 | Met, 3 | Met, 3 |
| T11 No caller FD growth | Met, 64 vs 65 | **Not met**, 66 vs 65 | **Not met**, 122 vs 104 | Met, 102 vs 104 |
| T12 Idle PSS growth within 40 MiB | Met, +24.0 | **Not met**, +42.4 | Met, +22.6 | **Not met**, +46.3 |
| U1 Grid: no failed tile, no blank tile | Met, 0/0 | Met, 0/0 | Met, 0/0 | Met, 0/0 |
| U2 Grid frame p95 within one 60 Hz frame of master | Met, +6 | Met, +5 | Met, -3 | Met, +6 |
| U3 Notification avatar within 2.5 s: idle, other, same | Met, 60/60/60 | Met, 60/60/60 | Met, 60/60/60 | Met, 60/60/60 |
| U3 The same with a group URL picture single-use | Met, 60/60/60 | **Not met**, 60/32/30 | Met, 60/60/60 | **Not met**, 60/43/41 |
| U4 Emoji within 250 ms: idle, other, same | Met, 60/60/60 | **Not met**, 52/60/60 | Met, 60/60/60 | **Not met**, 51/60/60 |
| U5 Cancel delivers nothing; next image within 2 s | Met, max 143 | Met, max 376 | Met, max 98 | Met, max 491 |

On stock Android the domain lane meets every BUDGET-v3 item on API 30. On
API 36 it misses T11: the app's caller FDs peaked at 122 against master's 104,
where round 4 measured 102. The reusable lane on the same build, which never
evicts or starts a second worker, peaked at 126 in the same run, so the peak
belongs to that build's process rather than to domains, but A3 is judged as
written and records the miss. Without the application zygote the domain lane
misses what it missed in round 4: T7, T8 both ways, T12, U4 and U3 with group
URL pictures. On API 30 it also misses T11 by one descriptor, 66 against 65,
where round 4 met it at 66 against 66.

U5's editor half now decodes in two picks' own domains, the cancelled pick's
and the next one's, so the next image pays a worker start: p50 48 to 51 ms with
the zygote and 287 to 332 ms without, against 17 to 18 ms on the reusable
lane, all well within 2 s.

##### Albums

Ten-photo albums, ten per lane per build in ABBA order, milliseconds. The
*Conversation* lanes decode every pick of the album in one outgoing domain, the
previous head's mapping, on this head's pool.

Run 2, builds of `2463aa3c1` (sent photos retired at acceptance):

| Ten-photo album, API 30, ms | Master | Reusable + zygote | Conversation + zygote | Pick + zygote | Conversation, no zygote | Pick, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Preparation, pick to previews shown: p50/p95 | 538/877, max 877 (10 albums) | 589/1016, max 1016 (10 albums) | 577/1016, max 1016 (10 albums) | 950/1421, max 1421 (10 albums) | 569/885, max 885 (10 albums) | 3753/4711, max 4711 (10 albums) |
| Preparation, pick to previews shown: one pick's p95 | 773 | 887 | 864 | 1190 | 778 | 4065 |
| Send, tap to encoded, then posters: p50/p95 | 554/559, max 559 (10 albums) | 585/671, max 671 (10 albums) | 578/651, max 651 (10 albums) | 957/1109, max 1109 (10 albums) | 550/557, max 557 (10 albums) | 6505/6843, max 6843 (10 albums) |
| Send, tap to encoded, then posters: one pick's p95 | 57 | 69 | 69 | 116 | 57 | 702 |
| Failed picks | 0 | 0 | 0 | 0 | 0 | 0 |

| Ten-photo album, API 36, ms | Master | Reusable + zygote | Conversation + zygote | Pick + zygote | Conversation, no zygote | Pick, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Preparation, pick to previews shown: p50/p95 | 505/855, max 855 (10 albums) | 465/853, max 853 (10 albums) | 491/737, max 737 (10 albums) | 945/1231, max 1231 (10 albums) | 540/824, max 824 (10 albums) | 2946/3795, max 3795 (10 albums) |
| Preparation, pick to previews shown: one pick's p95 | 715 | 698 | 543 | 1000 | 692 | 3098 |
| Send, tap to encoded, then posters: p50/p95 | 480/569, max 569 (10 albums) | 455/522, max 522 (10 albums) | 466/584, max 584 (10 albums) | 982/1057, max 1057 (10 albums) | 486/514, max 514 (10 albums) | 5410/5664, max 5664 (10 albums) |
| Send, tap to encoded, then posters: one pick's p95 | 59 | 55 | 52 | 117 | 53 | 576 |
| Failed picks | 0 | 0 | 0 | 0 | 0 | 0 |

Re-run, builds of `37d5befcf` (sent photos retired after their posters):

| Ten-photo album, API 30, ms | Master | Reusable + zygote | Conversation + zygote | Pick + zygote | Conversation, no zygote | Pick, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Preparation, pick to previews shown: p50/p95 | 654/1276, max 1276 (10 albums) | 789/1419, max 1419 (10 albums) | 751/1384, max 1384 (10 albums) | 1437/2321, max 2321 (10 albums) | 589/943, max 943 (10 albums) | 3809/4146, max 4146 (10 albums) |
| Preparation, pick to previews shown: one pick's p95 | 970 | 1208 | 1044 | 1907 | 793 | 3831 |
| Send, tap to encoded, then posters: p50/p95 | 574/921, max 921 (10 albums) | 735/863, max 863 (10 albums) | 728/847, max 847 (10 albums) | 1651/1994, max 1994 (10 albums) | 566/592, max 592 (10 albums) | 6179/6486, max 6486 (10 albums) |
| Send, tap to encoded, then posters: one pick's p95 | 88 | 96 | 88 | 241 | 61 | 719 |
| Failed picks | 0 | 0 | 0 | 0 | 0 | 0 |

| Ten-photo album, API 36, ms | Master | Reusable + zygote | Conversation + zygote | Pick + zygote | Conversation, no zygote | Pick, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Preparation, pick to previews shown: p50/p95 | 500/832, max 832 (10 albums) | 516/825, max 825 (10 albums) | 520/727, max 727 (10 albums) | 977/1237, max 1237 (10 albums) | 520/814, max 814 (10 albums) | 3038/3606, max 3606 (10 albums) |
| Preparation, pick to previews shown: one pick's p95 | 710 | 704 | 559 | 1086 | 692 | 3341 |
| Send, tap to encoded, then posters: p50/p95 | 488/522, max 522 (10 albums) | 477/567, max 567 (10 albums) | 475/582, max 582 (10 albums) | 1050/1134, max 1134 (10 albums) | 461/504, max 504 (10 albums) | 5228/5461, max 5461 (10 albums) |
| Send, tap to encoded, then posters: one pick's p95 | 54 | 56 | 57 | 123 | 50 | 614 |
| Failed picks | 0 | 0 | 0 | 0 | 0 | 0 |

Run 3, API 30 only, the same builds, each lane started below load 4:

| Ten-photo album, API 30, ms | Master | Reusable + zygote | Conversation + zygote | Pick + zygote | Conversation, no zygote | Pick, no zygote |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Preparation, pick to previews shown: p50/p95 | 560/905, max 905 (10 albums) | 553/967, max 967 (10 albums) | 553/954, max 954 (10 albums) | 898/1296, max 1296 (10 albums) | 606/934, max 934 (10 albums) | 3826/4486, max 4486 (10 albums) |
| Preparation, pick to previews shown: one pick's p95 | 792 | 844 | 824 | 1114 | 820 | 4126 |
| Send, tap to encoded, then posters: p50/p95 | 578/619, max 619 (10 albums) | 567/622, max 622 (10 albums) | 550/579, max 579 (10 albums) | 1026/1136, max 1136 (10 albums) | 584/614, max 614 (10 albums) | 6272/6663, max 6663 (10 albums) |
| Send, tap to encoded, then posters: one pick's p95 | 63 | 63 | 59 | 119 | 64 | 723 |
| Failed picks | 0 | 0 | 0 | 0 | 0 | 0 |

| Amendment item | API 30 pick + zygote | API 30 pick, no zygote | API 36 pick + zygote | API 36 pick, no zygote |
| --- | --- | --- | --- | --- |
| A1 Album preparation p95 within master + 1.0 s, all within 10 s, run 2 | Met, +544 over master 877, max 1421 | **Not met**, +3834 over master 877, max 4711 | Met, +376 over master 855, max 1231 | **Not met**, +2940 over master 855, max 3795 |
| A1 Album preparation p95 within master + 1.0 s, all within 10 s, re-run | **Not met**, +1045 over master 1276, max 2321 | **Not met**, +2870 over master 1276, max 4146 | Met, +405 over master 832, max 1237 | **Not met**, +2774 over master 832, max 3606 |
| A1 Album preparation p95 within master + 1.0 s, all within 10 s, run 3 | Met, +391 over master 905, max 1296 | **Not met**, +3581 over master 905, max 4486 | not run | not run |
| A2 Album send p95 within master + 1.0 s, all within 10 s, run 2 | Met, +550 over master 559, max 1109 | **Not met**, +6284 over master 559, max 6843 | Met, +488 over master 569, max 1057 | **Not met**, +5095 over master 569, max 5664 |
| A2 Album send p95 within master + 1.0 s, all within 10 s, re-run | **Not met**, +1073 over master 921, max 1994 | **Not met**, +5565 over master 921, max 6486 | Met, +612 over master 522, max 1134 | **Not met**, +4939 over master 522, max 5461 |
| A2 Album send p95 within master + 1.0 s, all within 10 s, run 3 | Met, +517 over master 619, max 1136 | **Not met**, +6044 over master 619, max 6663 | not run | not run |
| A3 No BUDGET-v3 item newly missed, run 2 | Met, no item newly missed | **Not met**, T11 newly missed by one FD (66 vs 65) | **Not met**, T11 newly missed (122 vs 104) | Met, no item newly missed |

With the application zygote, one worker per pick cost 0.4 to 0.6 s per
ten-photo album in run 2, in run 3 and on API 36 in the re-run: preparation p95
+376 to +544 ms over master, send p95 +488 to +612 ms, within A1 and A2.

On API 30 the stock lane met A1 and A2 in run 2 (+544 and +550 ms) and run 3
(+391 and +517 ms), and missed both in the re-run: preparation +1045 ms over
master's 1276, send +1073 over 921. That block is the one whose load log read
10.2 at its end; its reusable and conversation lanes ran about 200 to 450 ms
slower than in the other runs, while the pick lane, which starts and kills a
process per photo, slowed most. The miss is recorded, not discounted: on a
loaded API 30 emulator one worker per pick can cost about 1 s per album.

Without the application zygote every pick is a full process start, about 0.3 s,
and the three-worker bound admits three at a time, so preparation takes 3.6 to
4.7 s and A1 misses by 2.8 to 3.8 s. Sending reads each unprepared slot in its
own worker and then decodes its poster, each after evicting another pick's
worker: 0.55 to 0.7 s per photo, 5.5 to 6.8 s per album, so A2 misses by 4.9 to
6.3 s. A pick the composer already staged skips the send-time read, which is the
common case, but the harness measures the worst one. All albums stayed within
the 10 s ceiling. On GrapheneOS, whose exec spawning skips the application
zygote, these are lower bounds.

Options for GrapheneOS, none taken here:

- Raise the live limit and spares where workers are exec-spawned: more starts
  at once, for more idle memory, which T12 already misses there.
- Keep picks' workers through a send: posters follow the upload, by which time
  later picks' reads have evicted the earlier picks' workers; a larger bound
  while a send is in flight would save one start per photo.
- Accept the cost: a ten-photo album is prepared within 5 s and sent within
  7 s, against master's 1 s, and every photo is protected from every other.
- Measure on a GrapheneOS device first; the kit's album step does that.

##### Cost against the reusable default

Each comparison below sets *Domain + zygote* or *Pick + zygote* against
*Reusable + zygote* from the same run, the same APK on the same emulator in one
ABBA campaign. Throughput, memory and UI figures come from run 2, whose code on
those paths is the final code. Album figures come from the final code's runs:
API 30 from run 3, with the loaded re-run in brackets, and API 36 from the
re-run. Against keeping the
reusable default, which ships the same launch path, the domain lane costs: a
first image in a new domain of p50 41 to 52 ms against 3 to 4; a burst across
six conversations of p95 144 to 207 ms against 76 to 80; a cold chat list of
p95 200 to 248 ms against 52 to 61, slowest 249 to 298 against 54 to 64; three
live workers against one; and 16 to 18 MiB more idle memory (71.6 against 55.6
MiB on API 30, 83.5 against 65.5 on API 36). A ten-photo album costs
preparation p95 1296 ms against 967 on API 30 (re-run 2321 against 1419) and
1237 against 825 on API 36, and send p95 1136 against 622 (re-run 1994 against
863) and 1134 against 567. The single-conversation bursts, scroll rounds and
notification artwork are within noise of it. A group's URL picture in a
notification, single-use, arrives at p50 68 to 213 ms against 7 to 53, all
within 2.5 s. After a cancelled viewer decode the next image arrives at the
first attempt in 20 of 20 rounds, where the reusable lane needed a retry in all
20.

The grid's slowest tile on the domain lane in run 2 was 468 ms (API 30) and
279 ms (API 36) over four passes each, against the 10-second settle window, so
the window keeps a margin of more than 9.5 seconds and stays as it is.

##### Verification

The domain canary gains a per-pick case: a second pick of the same conversation
gets a fresh process holding nothing of the first, the first pick's later input
returns to its own worker, and after the pick is retired its next input meets a
new process with nothing left behind. Containment acceptance, which runs it,
passed 35 of 35 on API 30 and API 36 with the final build. Three earlier API 30
runs on the build of `a9443e30e`, which differed from it only in comment text,
each failed `AvCapabilityLogCaptureDeviceTest`: the parent process did not see
its own 512th log line from logd within 3 s. One of them also refused to
overwrite an earlier run's baseline report, which had not been moved aside. That
test is unchanged from master and outside the still-image path, and in isolation
on that emulator it failed 3 of 6 runs on the `4562586f8` build too, which had
passed 35 of 35 before.

#### Rejected alternatives

On the same emulators:

- Keeping the reusable worker separates no inputs; it is the comparison and the
  lane to restore.
- Per-input generations, this change's earlier proposal: emoji artwork misses
  its bound behind any burst, bursts are about ten times slower, and on
  GrapheneOS every input would pay a full process start.
- One worker for every group image or for the chat list: a group's image would
  reach a decoder that another group's image may have compromised.
- One outgoing worker per conversation (round 4) or per account (round 3): a
  hostile pick reads every later pick of that conversation, originals before
  any crop, blur or mark included. Round 5's single-use editor renders covered
  only the editor's own decodes.
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
  `PictureDomainCoverageTest` fails when a production avatar-loader call, a
  picture row with a URL or a photo-editor inspection, preview or render names
  no domain, or when production code passes a decoding loader function by
  reference. The avatar loader's fallback returns a domain an enclosing
  `MediaTrustScope` declared before Unique, so an undeclared avatar-loader call
  inside a declared scope inherits it; the guard reads call sites, not scopes,
  and requires every production avatar-loader call to name its domain, which
  leaves that loader's inheritance to test and harness code. Other production
  paths rely on scope inheritance by design: `MediaPipeline`'s decode functions
  default to the enclosing scope, the photo editor declares its pick's domain
  through its render context, and the notification presenter declares each
  picture slot's domain around its resolver. Whether a named domain is the right
  one rests on review and on the audit above.
- The avatar cache and its in-flight requests are keyed by URL, not domain. If
  one URL were both a person's published picture and a group's URL picture, the
  first request's domain would decode it for both.
- An outgoing worker serves one pick, so a hostile file reaches only itself; a
  pick's identity is its composer slot or document URI, and a caller that
  attributed two files to one pick would let them share a worker. A hostile
  file can still undo a crop, blur or mark of itself. In the local
  domain a hostile emoji file can draw a private contact picture in an emoji
  slot, on this device or in this user's own notification; nothing from the
  local domain is sent.
- A cooperative self-exit ends the worker process only. Android's escalation
  kill covers the process group. A forked descendant of a compromised worker is
  outside the Binder witness.
- One idle spare stays resident while a slot is free, plus the application
  zygote on stock Android.
- On stock Android, forks share the application zygote's layout for preloaded
  code, so one generation's address-space disclosure applies to later ones.
- Heap tagging through the application-zygote launch path is unqualified on
  MTE hardware; see [native heap memory tagging](native-heap-memory-tagging.md).
- On GrapheneOS, every new domain, every pick and every Unique input pays an
  exec-spawned process start; see the [proposed budget](#measurements-and-proposed-budget)
  for the lower bound measured without the application zygote. There, a
  ten-photo album took 3.6 to 4.7 s to prepare and 5.5 to 6.8 s to send against
  master's 0.8 to 1.3 s and 0.5 to 0.9 s, missing A1 and A2 of the
  [round 6 amendment](#round-6-one-worker-per-pick).
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

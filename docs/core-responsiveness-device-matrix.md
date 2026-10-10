# Core responsiveness acceptance matrix

This candidate covers Android #2250, #2266, #3150 and #3178. Startup issue #1921
remains owned by PR #3203 and is excluded. Native protocol, history limits,
storage and artifact pins are unchanged.

## Source-confirmed repairs

| Issue | Android repair and regression | Remaining acceptance |
|---|---|---|
| #2250 | One latest-owned head correction retargets a burst while the same user gesture generation owns the top viewport. Superseded correction cleanup cannot release its successor; the composition-owned input gate retains its existing minimum. Actual ChatRow layout joins the paused-clock middle/burst fixtures. | Production adjacent, middle and burst frame timings on GrapheneOS; gesture, pinned/dataset and animation-scale qualification. Geometry tests and a captured intermediate image alone do not prove smooth device frame pacing. |
| #2266 | Exceptional catch-up completion settles readiness instead of leaving Attempting active. Existing silent foreground/relay revalidation is preserved. The opt-in bounded WNPerf sink now attributes subscription open/completion/failure/cancellation, deliberate source changes, retry waits, foreground versus relay revalidation, catch-up outcomes, current readiness phase, rendered banner and effective delivery settings using closed fields. Retired attempts cannot attribute into replacement episodes. | Classify the reported five-second transition, verify zero Connecting frames on a healthy retained resume and exercise real stream termination, network recovery, lifecycle and delivery configurations. No native disconnect cause has been established by source inspection. |
| #3150 | After a bounded distant preposition, mention navigation reads the current target geometry before its final animation and remembers the actual approach coordinate. A keyboard/row measurement change therefore does not force a stale-offset corrective bounce. A short true newest-row mention reserves native layout room before placement, and physical offset validation rejects tail clamping. The return control remains available through gesture settlement, route snapshots preserve the required room, and later explicit navigation restores normal tail padding; legacy reply, unread and TTS callers retain their resolver contract. | Run the existing real 50-plus-unread/end-only-mention journey with fresh fixtures and paired frame distributions, window preparation and correction slices. This fixes a deterministic geometry handoff; it does not prove that every reported stall has that cause. |
| #3178 | Live bounded timeline consumption runs alongside group-state/roster initialization. A queued local A+B replacement can publish while roster enrichment is held, and unverified notification transcripts retain their independent disclosure gate. Normal timeline completion waits for initial metadata settlement; failure and disposal still unwind both consumers and close handles. | First live transcript draw with stopped, boundary-queued and replacement-only updates, remote catch-up held, reading anchors, IME variants and stale-owner cancellation. Correlate notification/projection/resume/draw phases; the existing IME liveness window is not changed speculatively. |

## Qualification record

Record the exact candidate SHA, artifact provenance, API/OS/device, fixture
identifier, delivery configuration and each result when executed. Keep test
identities and messages disposable. Do not publish account keys, group/message
identifiers, private database exports, raw log dumps or relay URLs in evidence.

| Evidence | Status when this file was created |
|---|---|
| Source regressions | Added; hosted Kotlin execution pending |
| Focused motion goldens | Four scoped recording tests passed; light/dark and 200% RTL PNGs inspected; CI verification pending |
| Manual guide and tooling | Validator passed; 64 tooling tests passed using a canonical macOS temporary fixture root |
| Stock Pixel measurements | Not run for this candidate |
| GrapheneOS matrix | Pending; only the stock Pixel is currently available |

The new permanent cases are CHL-029, CHL-030, CON-032 and CON-033. Their boxes
remain unchecked. Use the existing conversation paging/mention traces and
benchmark entrypoint; preparation, final position and total elapsed time are
reported separately from missed frame deadlines or repeated viewport writes.

For #2266, correlate the bounded WNPerf export with the existing WNWarmResume
lifecycle markers from a performance-selector build, using monotonic logcat
timestamps. WNWarmResume supplies process PID, anonymous activity token, runtime
generation, saved-state availability and the closed lifecycle classification.
WNPerf uses `count` for runtime generation, `attempt` for the subscription attempt,
`elapsed_ms` for the current episode and `duration_ms` on `connection_retry_wait`
for the scheduled backoff. Phase lines distinguish deliberate source changes
from completion, exception and cancellation; they never serialize error text.
Collect both streams for the filed lifecycle classification rather than inferring
a transport drop from a banner. Release WNPerf alone does not add Activity tracing.

Native readiness remains MDK-owned. If a capture demonstrates a native window,
processing or connection-supervision bottleneck, link the canonical MDK defect
and retain this Android issue until the reviewed artifact is adopted and the
affected acceptance passes. Do not compensate with another message cache,
unbounded history, polling loop or unconditional foreground catch-up/reopen.

Use `Refs` for this candidate while physical criteria remain outstanding. A
merged PR or green CI does not close these issues or their parent outcomes.

## Additional acceptance harnesses

The retained transcript now captures the already received subscription tail
before resume and awaits its actual commit within the existing presentation
deadline. It introduces no message cache, native snapshot replay, second
receiver or network wait. A projection that has not returned from `nextWindow`
is a separate native boundary and is not covered by this local receipt barrier.

`ConversationRetainedTranscriptFirstFrameAndroidTest` mounts the production
conversation screen. It records each actual Android root draw using painted-layer metadata, including cached display-list reuse. It checks the first live transcript with
consumed, queued and replacement-only windows, held roster enrichment, open
and denied IME visibility, an older reading anchor and disposal. Its stale A
negative control uses that same production composition and must reject painted
content even when the controller has B. Superseded or retired receipts do not
count as commits; they retain the existing bounded fallback.
`ChatListConnectionResumeDeviceTest` samples actual banner draws through a
retained and recreated Activity edges, detects an injected visible attempt, and exercises the
production recovery flash. Paired controller tests advance the real automatic
retry deadline for EOF and failure of either or both inputs. These controlled
cases do not classify an uncaptured real five-second recurrence.

The real mention list regressions add suspended height, window/header, repeated
tap and drag cases. The trace report reads receiver-scoped asynchronous process
tracks, counts measured correction phases and separates tap-to-reached-landing
from the intentional highlight dwell. Historical d19 and dff traces contain
these async phases, but their fixtures differ; they remain unpaired evidence.
A manual credential-free before/after artifact mode on the existing staging
workflow supports fresh owned-emulator fixtures with an identical native pin.
It cannot be installed on a personal Dev app. Hosted tests and the paired
measurement campaign must pass before these additional harnesses qualify the
remaining issues; Max's separate GrapheneOS acceptance is deferred.

### Focused hosted responsiveness validation

Dispatch `android-instrumented.yml` with `responsiveness_only=true` on the exact candidate ref to run the production transcript and connectivity Activity/draw tests. This uses a separate concurrency group, skips unrelated attachment and native verifier jobs, and fails if any declared case in those two classes is missing, skipped or failing. The default full instrumented suite remains required before readiness. The focused result does not establish physical-device or GrapheneOS acceptance.

# Core responsiveness acceptance matrix

This candidate covers Android #2250, #2266, #3150 and #3178. Startup issue #1921
remains owned by PR #3203 and is excluded. Native protocol, history limits,
storage and artifact pins are unchanged.

## Source-confirmed repairs

| Issue | Android repair and regression | Remaining acceptance |
|---|---|---|
| #2250 | One latest-owned head correction retargets a burst while the same user gesture generation owns the top viewport. Superseded correction cleanup cannot release its successor; the composition-owned input gate retains its existing minimum. Actual ChatRow layout joins the paused-clock middle/burst fixtures. | Production adjacent, middle and burst frame timings on GrapheneOS; gesture, pinned/dataset and animation-scale qualification. Geometry tests and a captured intermediate image alone do not prove smooth device frame pacing. |
| #2266 | Exceptional catch-up completion settles readiness instead of leaving Attempting active. Existing silent foreground/relay revalidation is preserved. The opt-in bounded WNPerf sink now attributes subscription open/completion/failure/cancellation, deliberate source changes, retry waits, foreground versus relay revalidation, catch-up outcomes, current readiness phase, rendered banner and effective delivery settings using closed fields. Retired attempts cannot attribute into replacement episodes. | Classify the reported five-second transition, verify zero Connecting frames on a healthy retained resume and exercise real stream termination, network recovery, lifecycle and delivery configurations. No native disconnect cause has been established by source inspection. |
| #3150 | After a bounded distant preposition, mention navigation reads the current target geometry before its final animation and remembers the actual approach coordinate. A keyboard/row measurement change therefore does not force a stale-offset corrective bounce. A short true newest-row mention reserves native layout room before placement, and physical offset validation rejects tail clamping. Later explicit navigation restores normal tail padding; legacy reply, unread and TTS callers retain their resolver contract. | Run the existing real 50-plus-unread/end-only-mention journey with fresh fixtures and paired frame distributions, window preparation and correction slices. This fixes a deterministic geometry handoff; it does not prove that every reported stall has that cause. |
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

# Isolated audio/video capability experiment

This debug-only experiment measures Android media API access from the app's
permissionless isolated UID. Attachment decoding, playback,
voice playback and fallback retain their existing production routes. Earlier API30
and API34 campaigns failed their A/V reply-deadline observations; these failures
remain part of the evidence. Current-source API 30 and API 36 qualification remains pending.
Issue #3374 remains open. The [feasibility decision](media-av-feasibility.md)
records the path inventory, retained results and remaining implementation gates.

## Fixed inputs and phases

The focused containment campaign uses the existing verified silent-black MP4 and
synthetic PCM WAV fixtures. It also submits a truncated MP4 and rejects empty or
overflowing descriptor ranges before starting a worker. Measurements for other formats remain pending.

Each admitted case receives one read-only selected descriptor. Separate cases
exercise retriever construction, selected-source metadata, the existing complete
poster probe, extractor tracks, audio codec creation/configuration/start and the
existing waveform algorithm. Complete playback and decoding require separate evidence. A waveform
`no_result` leaves the failing internal call unknown.

The worker reports isolation checks through the existing primitive reply frame.
A bounded debug READY transaction precedes platform construction. The parent
checks the immutable endpoint generation, reply capability, run token, case and
request, then records the kernel Binder UID and synchronous PID. A later terminal
reply must match that capability and UID; its one-way PID can be unavailable.
The experiment rejects same-UID replies,
network permission, initialized application state, a readable private canary or a
writable selected input. The production reply format retains its existing fields; correlated diagnostics
use a separate fixed log tag.

## Lifetime and output

The test fixture registers the exact endpoint's death recipient before sending
any descriptor. It revokes the reply capability and requests retirement before
waiting for observed Binder death. Only then can it read poster pixels into a
bounded parent-owned Bitmap or admit another case. Startup without an observed
Binder, missing READY identity, missing death and malformed replies stop the
campaign as unqualified. A failed close or unbind keeps the generation held.
The next case requires positive closure of both selected handle scopes, revocation
and successful once-only unbind and disposal of that exact generation.
The fixture treats disconnect, unbind, a retirement request and elapsed time as
unresolved lifetime observations.

Probe requests retain the existing 8-second deadline and 6-second watchdog.
Waveform requests retain their 40-second ceiling and have no soft refusal.
For other probes, an explicit debug-only soft refusal starts at 5 seconds. This
shortens the observation opportunity before the existing hard kill; it does not
establish that the platform operation would fail with a longer budget. A separate
notification thread sends a bounded typed negative frame only after acknowledged
READY and actual pre-parser isolation, canary and selected-input checks. The
synchronous callback validates the kernel caller UID and PID, request tuple and
current endpoint. It signals the parent and returns immediately; Binder dispatch
never waits for worker death. The parent awaits exact death separately.

One atomic winner owns both the terminal diagnostic and its send attempt. A late
parser completion cannot emit a second terminal record. The independent hard-kill
thread performs no logging or Binder send, and normal completion or a blocked
notification never cancels it. The parent still requires a timely authentic frame,
exact endpoint death and complete cleanup. A typed `worker_soft_timeout_no_result`
records `capability_status=unqualified_timeout` and `production_route=false`; it
reads no shared output. Missing or late replies retain the failing deadline path.
This negative characterization is distinct from successful media parsing, just
as an attributable parser IOException can produce a timely `no_result`.

After a timely reply, the fixture
allows a separate 2-second grace period for the exact Binder-death witness. Missing
or late replies remain unqualified. Missing death keeps the worker generation held.
The result records operation and retirement durations separately; total elapsed time
includes cleanup and output validation. These finite bounds remain separate from
measured performance budgets and production defaults.
Descendant lifetime remains outside the endpoint death proof. Pixel values stay
untrusted; the copied Bitmap supplies bounded private storage with authenticity
and atomicity outside its guarantees.

## Evidence and interpretation

`MediaAvCapabilityDeviceTest` belongs to focused containment and full suites,
without the ordinary PR smoke annotation. Its mandatory result is checked by the
existing dispatcher. Raw JSONL is written to the instrumented additional-output
directory alongside the existing baseline artifacts.

Every run has a fresh synthetic token. Worker diagnostics contain that token,
case/request IDs, fixed phases, outcomes, exception class/code, reported process
identity and monotonic time. Capture reads only the fixed tag, validates the
correlation and compares reported UID/PID with the accepted READY kernel identity. It excludes
exception messages, input paths and unrelated logs. Missing or ambiguous records
make the case unqualified; platform denial requires an attributable failure.

A typed terminal-reply deadline can be recorded after READY, exact death and
successful cleanup, allowing a fresh worker for the next independent case. It
reads no output. The failed case remains unqualified and the final test fails
after capture. Cancellation, unexpected errors and uncertain lifetime or cleanup
stop the remaining cases; they never enter this continuation path.

A successful test means the finite characterization completed with valid evidence.
Individual phases may report success, an exception or untyped `no_result`.
Review exact-head source, runtime API, JUnit, raw output and same-run APK evidence
before interpreting those outcomes. Production adoption and attachment-security closure require their separate
review and validation gates.

## Machine-readable matrix

Capture schema 3 records the app UID, selected fixture SHA-256, byte length and
format. Timely replies retain the isolation, network-permission, runtime-startup
and private-canary checks that the binding validated. Deadline rows retain their
missing terminal evidence as an explicit qualification gap. Schema 2 captures
remain historical evidence and lack the fields needed for independent matrix
validation.

After downloading the additional-output JSONL from the exact instrumented run:

```sh
python3 scripts/summarize_media_av_capability.py media-av-capability-TOKEN.jsonl
```

The analyzer rejects incomplete inventories, mixed identities and missing cleanup.
Its report separates a complete capture, a completed characterization and parser
support. A soft timeout can finish a negative characterization while its parser
support remains unqualified. A reply deadline fails characterization. APK/run
provenance and platform qualification require separate review even when the file
passes structural validation. Retain the raw JSONL with the exact APK and JUnit
results; the analyzer does not authenticate a locally supplied file.

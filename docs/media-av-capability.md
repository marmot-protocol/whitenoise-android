# Isolated audio/video capability experiment

This debug-only experiment measures Android media API access from the app's
permissionless isolated UID. Attachment decoding, playback,
voice playback and fallback retain their existing production routes. API30 and API36 results are pending.
Issue3374 remains open.

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
The parent stamps the actual Binder caller UID and rejects same-UID replies,
network permission, initialized application state, a readable private canary or a
writable selected input. The production reply format retains its existing fields; correlated diagnostics
use a separate fixed log tag.

## Lifetime and output

The test fixture registers the exact endpoint's death recipient before sending
any descriptor. It revokes the reply capability and requests retirement before
waiting for observed Binder death. Only then can it read poster pixels into a
bounded parent-owned Bitmap or admit another case. Startup without an observed
Binder, missing death and malformed replies stop the campaign as unqualified.
The fixture treats disconnect, unbind, a retirement request and elapsed time as
unresolved lifetime observations.

Probe requests retain the existing 8-second deadline and 6-second watchdog.
Waveform requests retain their 40-second ceiling. After a timely reply, the fixture
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
correlation and compares reported UID with the actual reply UID. It excludes
exception messages, input paths and unrelated logs. Missing or ambiguous records
make the case unqualified; platform denial requires an attributable failure.

A successful test means the finite characterization completed with valid evidence.
Individual phases may report success, an exception or untyped `no_result`.
Review exact-head source, runtime API, JUnit, raw output and same-run APK evidence
before interpreting those outcomes. Production adoption and attachment-security closure require their separate
review and validation gates.

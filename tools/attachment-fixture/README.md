# Controlled attachment fixture

Refs [Android tracker #2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779).
This test infrastructure changes no shipping runtime behavior. It uses generated
bytes, disposable identities and loopback endpoints. It never opens a personal
account, uploads to production, uninstalls an app or clears app data.

`fixture_server.py` implements a bounded Blossom upload/download endpoint with a
SQLite ledger outside the app process. Every request, successful body write and
terminal outcome is committed independently; reopening the same private run root
preserves all attempts. Use a new run root for an explicit reset. Blob hashes and
authorization headers are never exported in the ledger. The private database and
blob files must not be uploaded as CI artifacts.

`Control` supports delayed headers, unknown-length bodies, paced chunks, an exact
held prefix and explicit release without EOF. Strong ETag/Range/If-Range responses
permit deterministic client resume tests; an incompatible validator causes a
counted full response. Cancellation contracts detect peer close during a hold,
before completion or idle timeout. These host contracts do **not** qualify native
cancellation or checkpoint persistence.

`fixture_relay.py` is a minimal HTTP/1.1 WebSocket Nostr relay for generated test
identities. It preserves events while native clients reconnect within one host
run. It deliberately has no signature validation, production authentication or
durable relay-history guarantee. It binds only loopback and must never be exposed
as a production relay.

## Run

```bash
python3 -m unittest discover -s tools/attachment-fixture -p 'test_*.py'
python3 tools/attachment-fixture/host_baseline.py \
  --root /private/tmp/attachment-host-run \
  --output /private/tmp/attachment-host-report.json
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play
# Require a genuine shipping Android-controller send and exact retained native reads:
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller
# A separate matching Zapstore qualification:
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Zapstore
```

The script verifies the exact emulator before building or installing. It installs
the existing isolated `medialatency` package in place and runs only the guarded
probe. Ordinary instrumentation skips this opt-in test. The runner creates
`adb reverse --no-rebind` mappings and removes only its own mappings. Reports are
saved even on instrumentation timeout or cleanup failure; missing/incomplete
ledger evidence cannot become PASS. Optional `--private-debug` captures raw test
output only in the private run root for local debugging; it is off in CI and is
never copied into redacted reports.

Subprocess failures retain their operation, exit status and fixed diagnostic category. Failed emulator instrumentation also reads at most 100 recent crash records with a ten-second bound; reports retain only a matching package crash marker and a fixed exception category. Raw messages and command arguments stay out of artifacts. Recent-buffer diagnostics do not prove attribution to the current invocation and never qualify missing measurements. Physical fixtures retain the failure fields without collecting crash logs.

The optional `controller` sender mode configures only the generated group's loopback blob endpoint, then uses the shipping `ConversationController.sendAttachments` path without uploader, publisher or accepted-result fakes. Its reference comes from actual native history. The runner requires exact outgoing native lease bytes before and after native runtime reopen with acquisition unavailable; missing controller evidence fails even when transport and budgets pass. This remains a native-runtime lifecycle check, not full Android process restart or manual viewer/installer qualification.

The `controller-restart` mode force-stops only the isolated emulator fixture package between two instrumentation runs. Generated identities remain in a private fixture directory, then the second process verifies the restored native history and reads exact sent/received bytes ten times each while acquisition is unavailable. It asserts a different process id, keeps the HTTP ledger across both processes, and removes only its generated session after readback. The received reopen timing covers one read; the nine additional received and ten outgoing reads are correctness/bandwidth checks, not additional latency samples. No personal-device app or user data is stopped or cleared.

The `controller-cancel` mode uses the same genuine send, then holds the received HTTP body without EOF. It requires native acknowledgement and a server-recorded disconnect within five seconds, ten ordinary terminal joins and a thirty-second quiet interval with no extra acquisition, followed by an explicit Retry returning exact bytes. The separate `cancellation_checker.py` also enforces measured Java/native memory and overall latency ceilings. Reports remain failed when any event, budget or retry proof is missing. This checks the shared Android cancellation adapter; platform job replacement and external handoff need their own qualification.

The `controller-resume` and `controller-resume-changed` modes send a generated
4 MiB file through the shipping Android controller, hold the received ciphertext
at 2 MiB, and interrupt the connection without a deliberate Retry. Native retry
must send Range at that prefix with If-Range. The compatible case transfers only
the suffix; a changed validator returns a complete replacement body. Both cases
require exact plaintext, no readable partial lease, exactly two GET attempts,
independent completion/disconnect events and zero acquisition on subsequent
retained reads. `resume_checker.py` rejects missing or contradictory evidence.
These probes match MDK's canonical `hash.bin` locator so a different fallback
locator cannot replace the checkpoint under test. Ordinary small-file modes keep
their original upload descriptor and strict performance ceilings.

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-resume
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-resume-changed
```

The `controller-phases` mode sends two generated files through the shipping controller, a paced 32 MiB
file and a 1 MiB file, and subscribes to the same `nativeProgress` observer the conversation file card uses.
The paced file must publish one body phase whose byte count never moves backwards and whose total equals the
true ciphertext length, followed only by forward phases (verification, decryption) and Ready. The second file
hits a permanent miss: the probe must observe a real failure phase, acknowledge cancellation when that phase
is a live deferred attempt, then admit exactly one deliberate Retry that ends Ready with exact bytes.
`phases_checker.py` also reads the server ledger: one upload each, one successful body for the paced file,
only 404 attempts before the single successful body for the other, and a terminal outcome for every request.
Phase observation reports which transient phases the native feed actually published; an unobserved phase is
reported as such rather than inferred. The checker explicitly records `performance_qualified: false`.

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-phases
```

The `controller-media` mode qualifies genuine sent and received media across an Android process restart. The
prepare process sends a JPEG, a small video, 9 MiB and 24 MiB videos (either side of the 8 MiB memory-entry ceiling)
and a three-attachment album through the shipping controller, waits for each own send to publish its encrypted host
copy, reads that copy natively, then downloads every attachment as the receiver. The runner force-stops only the
isolated fixture package, and a second process opens the restored runtime with acquisition unavailable. Both
directions of every attachment must then read exact bytes natively and through the resolver, miss the memory cache,
and decode a first frame (a bitmap for an image, a frame and duration for a video). The 24 MiB send also holds its host
copy to prove native retention does not wait for it. `media_checker.py` reads the server ledger: one upload and one
acquisition per attachment before the restart boundary and **none** after it. Generated MP4 padding is a top-level
`free` box, so it decodes and plays; it tests size thresholds, not throughput. Attachment albums whose total payload
exceeds 32 MiB cannot be sent by the Android controller and are deferred, as are the conversation tiles themselves and
physical devices.

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-media
```

The `controller-apk` mode qualifies the received-APK boundary on a disposable emulator. The prepare stage sends five
generated files through the shipping controller, receives each genuinely, and requires exact bytes: a valid signed
package (this fixture's own APK), the same bytes labelled `application/octet-stream`, the same bytes labelled
`image/png`, a ZIP with a dex entry but no `AndroidManifest.xml`, and a truncated package. Each later stage reopens the
restored runtime in a new process with acquisition unavailable, deletes the published copy of the file, republishes it
from native retention (the stage fails if that read did not happen), and calls the real `openAttachmentExternally`. A self-update build is run with the install-unknown-apps app-op denied and
then allowed, which the **host** toggles between stages because changing it kills the app process; a Play build has no
installer and must answer `InstallUnsupported`. `apk_checker.py` requires, per distribution, the exact
`OpenAttachmentResult` and whether the system installer actually reached the screen. The probe watches the active
window for the package that handles APK installs after **every** dispatch, whatever status it returned: it waits for an
installer where one is expected, and otherwise watches for a full second so a launch behind `InvalidPackage`,
`InstallPermissionRequired` or `InstallUnsupported` is still seen. Each row records `installer_observed_ms`, and the
checker rejects a row that was not watched long enough. An installer that did appear is dismissed with Back. Nothing is
ever installed. The server
ledger must show exactly one acquisition per case across all stages, so a denied or blocked dispatch reuses the
completed download. Invalid packages must be rejected before any installer launch on every distribution.

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Zapstore reference-api30-arm64 controller-apk
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-apk
```

The `controller-matrix` mode is the controlled latency matrix for [#2785](https://github.com/marmot-protocol/whitenoise-android/issues/2785).
For each declared link (`unshaped`, `wifi` at 50/20 Mbit/s and 20 ms, `constrained` at 4/1 Mbit/s and 120 ms) the
**server** shapes bandwidth and latency in both directions, so a constrained label is verified outside the app: the
checker rejects a sample whose server-observed throughput exceeds the declared link. Each link runs genuine uploads
through the shipping controller (split into its synchronous preparation half and its upload-and-publish half), a cold
download, and a warm retained read, for 64 KiB, 1 MiB, 8 MiB and 30 MiB generated files (the 4/1 Mbit/s link stops at
8 MiB: a 30 MiB upload needs about 4.5 minutes there and the engine rejected its reference once the group epoch moved). The cold download records the
authoritative phase times from a 2 ms read-only poll next to the production subscription feed, plus sampled Java and
native peaks, so a delay is attributed to Android preparation, the FFI and engine, storage or transport instead of
guessed. Numbered ledger markers bracket request admission for every sample. Response bytes and completion events
remain owned by that request ID when server logging finishes after the next marker, so each sample owns its
requests, bytes and retries. The host then force-stops only the isolated package and a new process reads one kept file per size offline.
Every sample carries only a size, a repetition and measurements: no file name, identifier, URL, key or content.

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-matrix unshaped,wifi
# A short harness proof, never a measurement:
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-matrix unshaped,quick
python3 tools/attachment-fixture/matrix_compare.py --baseline base-1.json base-2.json base-3.json --candidate cand-1.json cand-2.json cand-3.json
python3 tools/attachment-fixture/matrix_budgets.py cand-1.json cand-2.json cand-3.json
```

`matrix_report.py` pools repeated runs at the sample level, so the observed spread includes run-to-run variation, and
aggregates samples into per-cell distributions (a p95 only from twenty samples or more), names the
dominant component and its layer for upload and download, and compares a candidate with a baseline: a change counts only
when it exceeds both the observed run spread and ten percent, and any difference in request count, byte count or
retries rejects the candidate whatever its latency. Emulator shaping does not establish physical Wi-Fi performance.

`matrix_budgets.py` applies `matrix_budgets.json` to a pooled set of runs: each budget is a ceiling of `fixed + per MiB x size` on a
median (with an optional target), a ceiling breach, a missing measurement or a budgeted link that was never measured fails,
and an unmet target is reported only. The command refuses (status 2) a report whose run did not qualify, whose raw matrix
fails the correctness check, or whose cohort mixes environments or link shapes, before it evaluates any ceiling.

The `controller-apk-recreation` mode qualifies **process death during a download** of a received APK. One generated
package is sent through the shipping controller, then the fixture holds its ciphertext body at 2 MiB and the probe
starts the real receiver download. Once the native feed shows at least 1 MiB received and the server records the held
body, the probe records the identity the next process needs and ends its own process with `Process.killProcess`, with
no cancel, pause or cleanup, as the system does when it reclaims memory. The host then releases the hold, waits for the
server's own `disconnect` of that acquisition so the two can never overlap, sets the install permission for a
self-update build, and launches a new process that reopens the restored runtime, records the native state it finds,
retries through the same `downloadAttachmentPlaintextSource` path a reader's tap uses, proves the published file by
size and SHA-256 against the sender's copy, and only then calls the real `openAttachmentExternally`.
`apk_recreation_checker.py` requires exactly one upload and exactly two acquisitions of it, the first held at the
prefix and ended by the disconnect with no completion, the second started after that disconnect and delivering exactly
the bytes it still needed (it records whether the replacement resumed from the committed prefix or restarted), and the
expected platform outcome per distribution. Nothing is ever installed, cancelled or force-stopped by the host.

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Zapstore reference-api30-arm64 controller-apk-recreation
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-apk-recreation
```

The `controller-forward` mode measures the shipping forward path for a small text attachment
([#2556](https://github.com/marmot-protocol/whitenoise-android/issues/2556)). Two generated identities share three
loopback groups: the author's source chat, the forwarder's own source chat and a destination chat. The author sends one
generated 1 KiB `text/plain` file; the forwarder then sends the same bytes directly into the destination five times
through `ConversationController.sendAttachments` as the baseline, and forwards through `startForwardMessages` five times
each with the source **uncached** (never opened), **retained** (opened once, so held only in native retention) and
**cached** (the forwarder's own send, whose encrypted host copy the shipping publication wrote). Each forward restarts a
local diagnostics session and records the app's own `op=message_forward` phase lines (source lookup, source download,
materialization, destination upload, commit-lock wait, publication, terminal), the time to the terminal state, and the
author's genuine receipt and exact read of the forwarded copy. A numbered ledger marker brackets every sample.
`forward_checker.py` requires every phase to be timed, exactly one completed message and one attachment per forward with
no retry or convergence, the memory, host-disk and native layers in the state the variant declares, a source download
only for the uncached source (a retained or host-cached source is served locally and never crosses the network again),
exactly one receipt per forwarded copy, one distinct destination message per send, and the issue's per-forward ceilings
(15 s for a retained or cached source, 30 s uncached) as loopback regression guards. It reports each variant's slowest forward against the direct-send median and the issue's two-times
relation, but does not fail on that relation, because loopback fixed costs dominate both numbers. It records
`physical_device_qualified: false`; the physical cached and uncached matrices on known-responsive infrastructure remain
separate.

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-forward
```

These are **transport interruption** checks. They do not simulate a JobScheduler
stop or Android process death. Latency and sampled Java/native peaks remain in
the report, but representative 4 MiB performance is explicitly unqualified;
the 1 KiB performance checker is inapplicable rather than relaxed.

Performance checks use explicit environment profiles: local API30 arm64 defaults to `reference-api30-arm64`; CI passes `ci-api34-x86_64` as the script's third argument. The runner verifies actual API/ABI and enforces every sample through `budget_checker.py` before transport qualification. Violations are included in the saved report and fail the command; no successful HTTP transfer can override them. To check only the performance fields of an existing report:

```bash
python3 tools/attachment-fixture/budget_checker.py report.json --profile reference-api30-arm64
```

See the [baseline report](../../docs/performance-data/attachment-fixture-baseline-2026-10-01.md) for method, phase/memory ceilings, failures, host-only versus Android results and qualification boundaries. Original raw sessions are stored in the durable [evidence archive and checksum manifest](../../docs/performance-data/attachment-evidence-manifest-2026-10-01.md); `verify_evidence.py` anonymously verifies/extracts every original member. Seven-day CI artifacts are only a convenience for new runs. Reports exclude private database/blobs and optional instrumentation transcripts.

### Explicit physical-fixture qualification

The runner still defaults to disposable emulators. Physical testing requires both
`--serial SERIAL --physical-fixture-serial SERIAL` and
`--budget-profile pixel-api37-arm64`; a mismatched serial, device type or API/ABI
fails. Update only the isolated `dev.ipf.whitenoise.android.medialatency` app/test
identities in place after a readable private backup and installed/candidate
signature verification. Invoke `am instrument` directly; never uninstall or clear
an app. The runner only manages its own generated sessions and adb reverses.

The Pixel profile applies the existing 1500 ms cold / 150 ms retained-read /
32 MiB Java / 128 MiB native regression ceilings without relaxing them. It is a
1 KiB fixture guard, not a calibrated physical-device performance SLO. The
controller, held-cancellation and process-restart modes work with this explicit
opt-in. A passing physical fixture does not qualify manual UI flows, real platform
job lifetime or representative large-file performance.

#### Physical received-APK gate

`apk_physical_runner.py` drives the same `controller-apk` probe on one explicitly authorized physical device. It has
three commands and none of them ever uninstalls, clears, downgrades or grants anything:

- `preflight` is read-only: it verifies the serial, the non-emulator check and the `pixel-api37-arm64` API/ABI,
  lists which isolated packages are installed, lists existing reverses and the current install app-op mode.
- `install` updates only `dev.ipf.whitenoise.android.medialatency` and its `.test` package in place with
  `adb install -r -t --user 0`, after `aapt2` package-name checks, `apksigner` signer parity between both candidates
  and the installed APKs (pulled read-only as the retained restore copies). It needs `--confirm-in-place-update`, and a
  package absent from the device additionally needs `--allow-fresh-install-of-isolated-identity`. It proves the
  device's `sha256sum` of each installed APK and writes an install receipt into the private backup directory.
- `run` refuses without `--owner-present-device-idle`, `--allow-installer-on-screen` and, for a self-update build,
  `--allow-install-app-op-toggle` (refused for Play, which has no app-op). It records the isolated package's original
  app-op mode and restores it, uses `adb reverse --no-rebind` and removes only its own mappings, and keeps failed and
  partial stages in the redacted report. Optional selectors close named gaps: `--cancel-retry` runs the shared
  held-body cancellation probe on the valid package before publication, `--large-apk PATH` registers a host-built
  30 to 31 MiB signed package, which `apksigner` from `--build-tools` must verify before the device is contacted, on the server's `/__payload/` endpoint (outside the counted acquisition ledger) and
  sends it through the shipping controller, `--no-installer-branch` dispatches the valid package through a context
  whose launch raises `ActivityNotFoundException`, which the checker reports as simulated, never as a platform state.

`apk_payload.py` builds that payload on the host from the built isolated test APK: one stored pad entry, `zipalign`,
then `apksigner sign` with the debug key, so the package is genuinely signed and within the 32 MiB Android sender cap.
Nothing installs it. The exact preconditions, owner authorizations, command order, evidence and restore steps are in
the [physical gate plan](../../docs/performance-data/attachment-apk-physical-gate-plan.md), which also states that
the Pixel results recorded so far and which criteria the gate still does not cover.

### Unknown-length native control

Run `bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-unknown-length`
(or the corresponding Zapstore/API36 environment) on an explicitly owned emulator.
The generated 4 MiB body has no Content-Length; native total/fraction must remain
unknown while positive bytes are rendered in a real Android file control. Exact
plaintext, 48 dp localized RTL/large-font semantics and zero repeat acquisition
are required. `unknown_length_checker.py` rejects incomplete evidence. This mode
qualifies functional behavior only; representative performance and complete
manual flows remain deferred. See the [source/APK qualification report](../../docs/performance-data/attachment-unknown-length-2026-10-03.md).

### Real Android background and screen lock

Use `controller-background` or `controller-lock` with the explicit emulator and
profile, for example:

```bash
bash scripts/run-controlled-attachment-fixture.sh emulator-5554 Play reference-api30-arm64 controller-background
bash scripts/run-controlled-attachment-fixture.sh emulator-5558 Zapstore reference-api36-arm64 controller-lock
```

Both require shipping Android scheduling, a stopped real Activity, independent
late HTTP bytes and a still-active transfer after 30 seconds. The lock mode checks
screen-off and Android keyguard, temporarily enables the owned emulator's disabled
keyguard if needed, then restores its original setting. A completed foreground
body or background-only evidence cannot pass lock qualification. Runtime observer
binding is test-only and points at generated peers; services, native IO and HTTP
are real. `background_checker.py` preserves measured timing/memory separately from
performance and rejects incomplete proof. See the [background report](../../docs/performance-data/attachment-background-2026-10-03.md)
and [screen-lock report](../../docs/performance-data/attachment-screen-lock-2026-10-03.md).
Actual automatic-job stop/resume and full Open/Save/network-policy/manual flows
remain separate; these modes never authorize physical-device actions.

### Actual automatic Android scheduler interruption

`controller-automatic-resume` registers the shipping connectivity listener that the
injected fixture constructor normally omits, enables the generated account's document
matrix, and waits for actual validated Android connectivity. It enqueues ordinary
WorkManager work without interactive intent or a foreground/user-initiated service.
After a real native 2 MiB prefix is held, the Activity leaves TOP. The fixture finds
that WorkSpec's exact platform job and asks Android to enforce its timeout. It must
observe the same WorkSpec return from RUNNING through ENQUEUED to RUNNING and SUCCEEDED,
and record the real coarse stop reason and first-run WorkManager attempt.

A separately controlled socket interruption forces native compatible Range/If-Range
recovery. The resumed suffix is held again until the platform worker has returned,
then released and verified exactly. No deliberate Retry, interactive read, force-run,
or acquisition record is seeded. The HTTP checker requires two GETs with no prefix
retransfer. This combines actual scheduler stop with independent transport failure;
it does not kill the app process, simulate every policy transition, or qualify
representative performance. All original small-file budgets stay unchanged.

Android's resumed RUNNING state does not start MDK's native retry clock. After
observing that state, the fixture allows up to 60 seconds for the independently
recorded resumed body, within the existing 120-second probe deadline. This is a
functional recovery allowance, not a latency guarantee. The resumed body is accepted
only after a native observation newer than the position captured before stopping
the job. Observation indices keep increasing when the diagnostic buffer rolls over;
an unchanged phase/attempt/retry delay on a fresh emission still counts as new evidence.
Terminal native transfer decisions fail immediately. Reports include the pre-stop
position and only the last 12 indexed native phase, attempt and remaining retry-delay
observations, with no account/attachment references,
URLs or error text. The two-GET, Range/If-Range, exact-byte and no-manual-retry
requirements are unchanged.

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

These are **transport interruption** checks. They do not simulate a JobScheduler
stop or Android process death. Latency and sampled Java/native peaks remain in
the report, but representative 4 MiB performance is explicitly unqualified;
the 1 KiB performance checker is inapplicable rather than relaxed. Full issue
closure requires the remaining [five-issue qualification](../../docs/attachment-remaining-closure.md).

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

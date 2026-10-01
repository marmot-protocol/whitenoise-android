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

The probe publishes a genuine 1 KiB file from a native fixture identity to another
native identity, waits for native received history, then uses Android's production
resolver and host acquisition gate. Cold and ten retained consumers compare exact plaintext, asserting empty
Android memory/disk caches before every retained read. After cold completion the
control channel makes acquisition requests return 503 while still counting
each attempt. One further read closes and reopens the native database under the
same unavailable endpoint. This is native runtime reopen, not app process restart
or whole-device offline qualification. The server must see exactly one complete acquisition body,
equal to the actual uploaded ciphertext size. It also queries the genuine native
sender's canonical local availability without acquiring it. That query alone
does not qualify Android's send controller or outgoing restart/offline retention.

Memory fields are **10 ms sampled absolute Java/native heap peaks**, with initial
and final samples, rather than allocation deltas or guaranteed maxima. Cold and
retained resolver elapsed times include the sampler lifecycle. The host baseline
reports host Python/RSS memory separately; it cannot stand in for Android memory.
Successful socket-write bytes exclude HTTP headers, TCP retransmits and total
link traffic, and can include bytes buffered before a disconnect. Record each
failed body, rather than guessing that buffered bytes reached the consumer.

## Qualification boundaries

The foundation requires passing host contracts and an actual small received-file
baseline. Its CI jobs execute both distribution variants and archive aggregate
reports; configuration alone does not establish passing hosted evidence.

The dedicated MDK large-file sender, Android controller genuine-send retention,
process restart/offline matrix, protected platform worker/service lifetime,
external Open/Save/installer handoff, physical-device matrix and production
endpoint capability probes remain separate qualification. Do not infer those
outcomes from a generated row, a supplier count, a direct native download, a
native runtime reopen or the host server's Range support.

Promotion/retry changes stay gated on MDK #2134, outgoing retention on MDK #2135,
and timeout-repair claims on Android #2936 or its qualified successor. Android
#2973 is already in the implementation base. Shared progress changes still need
Datawav's written ownership coordination. Danny's MDK #2106 remains untouched;
agent-sender rows are deferred. No issue closure, merge or release is authorized.

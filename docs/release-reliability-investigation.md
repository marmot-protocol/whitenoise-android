# Release reliability: verified boundaries and remaining work

This investigation covers five release-reliability reports against Android source
`4b4e2224a32e141b1a1f9c553546e079c8792560`. Regression tests protect the existing
boundaries below; passing them does not establish that an unreproduced report is fixed.

## Offline signer rehearsal

[Replay late NIP-46 responses #2844](https://github.com/marmot-protocol/whitenoise-android/issues/2844)
already has replay for both subscription/request orderings. The remaining fixture
changes retain only 16 recent responses, match subscription filters for live and
replayed replies, honor `CLOSE`, and replace a socket's reply subscription atomically.
Malformed requests leave the existing subscription unchanged. Socket disconnect
removes its subscription.

`scripts/rehearse-zsp_test.go` exercises the actual loopback WebSocket handler with
disposable keys and protocol barriers, without timing sleeps. The hosted offline
contract runs those tests with the race detector and rehearses the pinned ZSP
publisher. This fixture remains deliberately limited to one reply subscription per
socket; it is not a production relay or a public signer.

## Large attachments

[Send large group attachments without retaining whole files #2284](https://github.com/marmot-protocol/whitenoise-android/issues/2284)
is not solved by increasing the current picker's 32 MiB limit. The picker,
retained-send cache, native draft preparation and byte-array FFI callers still
materialize payloads. Increasing that limit alone can exhaust the JVM heap and
evict retry inputs.

`StagedDocumentRead` already copies private snapshots through 64 KiB buffers and
has cancellation, overflow, input-close failure, permissions and lease tests. The
added long-budget regression proves read lengths do not overflow when a budget
exceeds `Int.MAX_VALUE`; it does not upload such a file or raise any UI limit.

[MDK file-transfer support #2215](https://github.com/marmot-protocol/mdk/pull/2215)
is published in snapshot `d4e91c8d90293de1a664a950a134f747dbb197a8`. Its file-upload
batch bound is 900 MiB of ciphertext, including authentication tags; actual endpoint
limits can be lower. The Android baseline still pins `7692e266`, and the newer
snapshot's file methods do not provide a file-backed composer-draft setter.
`PendingAttachment.sourceFile` and `FileUploadSources` therefore remain preparation
scaffolding, not an enabled large-file send path.

Before enabling the feature, qualify native draft retention/reply admission, stable
source ownership through process recreation, mixed-album ordering and captions,
account switching, progress, cancellation and retry. Consume a reviewed immutable
SDK rather than copying encryption, endpoint policy or durable-send logic into Android.
Measure a generated large transfer on a real phone; a host staging test is not that evidence.

## Public group-photo uploads

[Group-photo InvalidMediaReference #2899](https://github.com/marmot-protocol/whitenoise-android/issues/2899)
includes an upload-stage error report from `2026.9.21 (19)` on Android 15 / API 35.
The image, signer and network cohort remain unknown; no additional reporter
artifacts are available. The current public-avatar path is `GroupAvatarUploadAttempt`, distinct from
the encrypted group-image mutation. MDK's default public-profile upload endpoint
is `https://blossom.primal.net`.

An upload-stage `InvalidMediaReference` can represent MDK's destination-safety refusal;
it does not prove malformed image bytes. Keep private-address, DNS and HTTPS checks.
Existing tests cover stage-specific diagnostics, stale owners, unsafe descriptors,
an unchanged old avatar after refusal, and a deliberate retry. Added cancellation
tests cover preparation and publication as well as upload. They do not establish
that the reported remote upload succeeds.

A root-cause fix still needs a controlled supported JPEG/PNG attempt against the
current native uploader, separating preparation, signer approval, endpoint dialing,
HTTP/descriptor verification and group publication. Preserve the saved avatar and
loading/retry state on failure. Do not replace a refused destination with an unsafe fallback.

## Biometric success handoff

[Immediate crash after biometric unlock #2927](https://github.com/marmot-protocol/whitenoise-android/issues/2927)
has no crash trace or exact installed cohort. Current success checks logical session
and Activity-host ownership before consuming the exact expected authenticated cipher.
Failed crypto leaves the opaque lock cover visible; timestamp storage has bounded
corruption recovery. Tests already cover mismatched/missing/uninitialized ciphers;
the added regression checks a GCM-finalization failure cannot escape verification.

The available GrapheneOS fixture checked during this investigation reports API 37,
`CredentialType: NONE`, and zero enrolled fingerprints. It cannot exercise successful
biometric authentication. No credential enrollment or security-setting change is
part of this investigation. Do not call lifecycle smoke or JVM crypto tests a physical
biometric PASS. Real biometric success, recreation, cold/warm return and notification
entry still need a suitably configured device and source-bound crash evidence.

## Diagnostics choice across updates

[Repeated diagnostics consent #2820](https://github.com/marmot-protocol/whitenoise-android/issues/2820)
does not currently have an app-version-based receipt. `AuditUploadConsent` stores
disclosure revision 2 in SharedPreferences. `UsageDiagnosticsController` projects
MDK's durable decision without an Android-owned persistent telemetry grant.

Added regressions reopen the audit acknowledgement across repeated startup and
recreate the diagnostics projection for both grant and decline without another
consent write. These host tests do not simulate replacing APKs or native databases.

The pinned MDK compares policy revision, registry fingerprint and destination/operator
scope before restoring a grant. Its fingerprint includes the approved native and
host event schemas; ordinary app-version metadata is excluded. A changed schema or
scope can legitimately require renewal. A read/write failure must stay retryable,
not be converted into consent.

Qualify a real in-place upgrade with unchanged scope, then contrast a genuine scope
expansion and read/write failure. Without the reported cohort or reproduction, do
not auto-grant, suppress a required renewal, or close the repeated-prompt report.

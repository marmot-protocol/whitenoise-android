# Onboarding and invitation device fixtures

These tests use disposable native stores and in-process loopback relays. They never
need a real account, a public-relay invitation, or a reset of installed app data.
Use an isolated API 36 ARM64 emulator. Never run state-wiping connected tests on a
personal device; use in-place installs and explicit instrumentation instead.

The regular `DiscoveryInvitationFfiIntegrationTest`,
`DiscoveryInvitationRejectionFfiTest`, `OnboardingRelayRepairFfiIntegrationTest`
and `OnboardingRelayRepairUiIntegrationTest` run without external fixture files.
They cover recovery, authentication-required retry, signed deletion, missing
inbox, advertised-route success, exact revision approval, restart and the actual
setup screen's Review / Cancel / Fix / Save sequence.

## Signed MLS rejection vectors

`SignedInvitationVectorDeviceTest` requires `-e signedInvitationE2e true`. Generate
fresh vectors from an isolated checkout of MDK commit
`d4e91c8d90293de1a664a950a134f747dbb197a8`. Append
`export-invitation-vectors.rs` to
`crates/marmot-app/src/tests/invite_recovery.rs` in that disposable checkout, then
run from its root:

```sh
ANDROID_VECTOR_OUTPUT=/private/tmp/pr3201-signed-vectors.json \
  cargo test -p marmot-app --lib export_android_3137_vectors -- \
  --exact tests::invite_recovery::export_android_3137_vectors
```

The output path must not exist. The exporter uses genuine MDK MLS builders and
signs public Nostr records with fresh disposable keys; it exports no private keys.
The test verifies each wire signature, rejects vectors older than one hour, waits
for the short-lived package to expire, and binds loopback ports 44111 and 44112.
Do not substitute arbitrary malformed bytes for the legacy, incompatible or
expired package cases. Regenerate when the pin changes, reviewing the exporter
against the new native builders.

Install the matching Dev application and test APKs in place on the emulator.
With `ANDROID_SERIAL` set to that emulator, copy the fixture and execute:

```sh
adb shell 'run-as dev.ipf.whitenoise.android.dev sh -c "cat > cache/pr3201-signed-vectors.json"' \
  < /private/tmp/pr3201-signed-vectors.json
adb shell am instrument -w -r -e signedInvitationE2e true \
  -e class dev.ipf.whitenoise.android.state.SignedInvitationVectorDeviceTest \
  dev.ipf.whitenoise.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Both Create group and Add members must reject every case, leave the chat set,
roster and epoch unchanged, and make zero Welcome publication attempts. The
incompatible case exposes MDK's existing runtime error with the required-capability
explanation; the other cases expose `InvalidKeyPackageEvent`.

## Official Amber interoperability

`AmberRelayRepairDeviceTest` is opt-in with `-e amberRelayE2e true`. It additionally
rejects physical devices. Install official Amber on the isolated emulator and
import the **public scalar-one test key**, never a personal identity:

```text
nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl
```

Select manual approval and **Never** for automatic signing. Complete Amber's
initial Android permission dialogs before running the fixture. Finish any prior
signer request first. This known public key is unsuitable for real accounts.

The test uses a separate native store, rejects a real NIP-55 request, recreates
the host Activity, reconnects the signer, explicitly retries, accepts the new
request and checks exactly one publication attempt. It does not qualify Amber's
Back gesture or full process death. Native preview cancellation and store reopen
are covered separately; do not describe these as equivalent device journeys.

Verified fixture dependency: Amber v6.6.7 ARM64, APK SHA-256
`99989a7ad06e60a6e6a9f0fe1751724ed11d7346acf8832ad758ae3a22e03c73`.
Keep APK hashes, instrumentation logs and exact source commit with test evidence.

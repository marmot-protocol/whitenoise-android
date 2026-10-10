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
request and checks exactly one publication attempt. With
`-e amberBackBeforeReject true`, it also verifies that official Amber keeps its
sheet pending after Back, then explicitly rejects before the retry. Back alone
is not a cancellation result. Full external-signer process replacement is not
qualified by this fixture. Native preview cancellation is covered separately.

Verified fixture dependency: Amber v6.6.7 ARM64, APK SHA-256
`99989a7ad06e60a6e6a9f0fe1751724ed11d7346acf8832ad758ae3a22e03c73`.
Keep APK hashes, instrumentation logs and exact source commit with test evidence.

## Isolated physical-device lifecycle check

`RelaySetupLifecycleDeviceTest` runs only in the test-only `maestrolab` package
built with `scripts/maestro-runtime.init.gradle`. It imports the public scalar-one
identity through MainActivity with native traffic redirected to a loopback relay.
It checks Activity recreation, Later/resume, cancellation, account replacement
with a captured stale action, relay rejection and an explicit identical-event retry.
Acknowledged publication followed by auth-required readback stays incomplete;
restoring reads and choosing Retry must pass without publishing again.
It does not access the user's normal Dev identity or use the personal Amber app.

Install both matching fixture APKs **in place** with `adb install -r -t`, disable
only the fixture package's `BackgroundConnectionBootReceiver`, and run:

```sh
adb shell am instrument -w -r -e relayLifecycleE2e true \
  -e class dev.ipf.whitenoise.android.maestro.RelaySetupLifecycleDeviceTest \
  dev.ipf.whitenoise.android.maestrolab.test/dev.ipf.whitenoise.android.maestro.MaestroFixtureRunner
```

For real process termination, pass `-e relayProcessPhase prepare` and
`-e relayProcessToken <fresh-lowercase-UUID>`. Wait for the fixture's private
`files/relay-lifecycle-<UUID>/checkpoint.json` marker, then force-stop **only**
`dev.ipf.whitenoise.android.maestrolab`. Rerun with `relayProcessPhase restore`
and the same token. The restore phase compares the persisted proposal, mounts
production setup, cancels it, and verifies zero publication. It binds port 44201;
run phases sequentially. Preparation intentionally remains running until the host
stops the process, so its interrupted instrumentation result is not a test pass.
Never substitute the user's Dev package or uninstall/clear either package.

For the mixed-health import and changed-source cases, append
`export-relay-declarations.rs` to the same disposable pinned MDK test module and
run `cargo test -p marmot-app --lib export_android_mixed_relay_declarations`
with `ANDROID_RELAY_VECTOR_OUTPUT` set to a new output path. This produces signed
NIP-65/inbox records for the public scalar-one key with retained roles, duplicate
endpoints, extension tags and opaque content. Copy the file into the isolated
package's `cache/pr3201-mixed-relays.json`. Add `-e relayMixedFixture true` for
usable declarations imported through MainActivity, or `-e relayChangedSource true`
to introduce the declarations after a missing-source preview. Both bind port
44202 and must run sequentially. These switches are distinct from the process
restart phase and must not be combined with it.

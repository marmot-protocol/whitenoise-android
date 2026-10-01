# Public Nostr event verification: validation plan

Scope: the stateless MarmotKit verifier, public event cards and author metadata,
Zapstore release discovery, and signed APK asset selection.

## Manual checks

Use the PR's current preview and record the version/head shown in About. Preview
APKs use a test package/signature; a successful preview installation cannot
demonstrate a production self-update. Use disposable test accounts for the
chat checks and an authorized controlled release for an actual update.

1. In a test conversation, send links copied from a Nostr client to an existing
   note (`note` or `nevent`) and article (`naddr`). Include a public note with
   emoji, non-Latin text, quotes, backslashes and line breaks. Expect the right
   card and reader contents. Author metadata may arrive after the card.
2. Repeat with supported video, software-release and file-metadata events.
   Test repeated links, multiple different links, scrolling, reopening the
   conversation and a fresh app-process start. Cards must remain responsive and
   must not substitute another event or author.
3. With airplane mode enabled, open an uncached reference. Expect a bounded
   recoverable failure. Restore connectivity and retry. Switch accounts or
   conversations during resolution; a late result must not update the new
   conversation. A previously cached card can remain visible while offline.
4. On a Zapstore build, open Settings > App updates and check for updates. Test
   a current version, a newer controlled version, network failure and retry.
   Follow `SYS-008` and `SYS-010` in
   [the manual release checklist](manual-release-testing.md). Confirm download,
   cancellation, checksum/package verification, permission return and installer
   handoff. Installation must remain unavailable until all trust checks pass.
5. On a Play build, follow `SYS-009`: no Zapstore APK download or in-app installer
   should be available. For production upgrade testing, use an older build with
   the same package and production signing identity; preserve its test account
   through the upgrade.

Do not forge events or publish experimental releases to complete this manual
checklist. The developer suites below exercise adversarial inputs deterministically.

## Developer checks

Run these on the exact PR head. Follow the host's build/device admission rules.

```sh
./gradlew :app:testDevZapstoreDebugUnitTest \
  --tests '*NostrEventVerifierTest' --tests '*NostrEventCardResolverTest' \
  --tests '*ZapstoreEventsTest' --tests '*ZapstoreReleaseClientTest' \
  --tests '*ZapstoreAssetEventsTest' --tests '*AppSelfUpdateDownloaderTest' \
  --tests '*AppSelfUpdateInstallerTest' --tests '*AppSelfUpdatePackagePolicyTest' \
  --tests '*AppSelfUpdateVerificationBoundaryTest'
python3 -m unittest scripts/test_run_android_instrumented_dispatch.py
./gradlew :cryptoBenchmark:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.core.nostr.NostrEventVerifierInstrumentedTest
```

The JVM suites cover parsing/serialization and caller policies using injected
verification decisions. They do not load the Android native verifier.

The isolated, minified instrumented APK uses the production DTO and verifier
with the pinned MarmotKit artifact and no account/database initialization. It
checks signed text/release fixtures, mutations of signed fields, forged content
with recomputed IDs, out-of-range signature scalars, out-of-range and in-range
off-curve public keys, malformed fields, Unicode/control characters, numeric
boundaries and concurrent valid/forged inputs. The boundary fixtures were
independently signed using coincurve 21.0.0/libsecp256k1, public test scalar 1 and
zero auxiliary randomness; the kind-overflow fixture has a valid signature but
must fail the unsigned-16-bit kind contract.

Run this suite on both arm64 hardware and the CI x86_64 emulator. Other packaged
ABIs need their own runtime tests before claiming equivalent device coverage.
Require successful CI at the same head, including both distribution flavors,
fuzz regression replay, minified ART execution, CodeQL and APK reproducibility.

For timing, run `Bip340PhysicalBenchmark` on real hardware and retain its raw
samples, device state, APK hashes and source inputs as described in
[the benchmark method](bip340-verifier-benchmark.md). Timing excludes native
first-call initialization; it is not an end-to-end card-loading measurement.

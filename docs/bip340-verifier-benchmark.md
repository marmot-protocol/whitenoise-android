# BIP-340 verifier comparison

`cryptoBenchmark` is an isolated test app. Its optimized release APK compiles
the production `BIP340.kt` and `NostrEvent.kt` sources directly, plus the
benchmark-only copy of the removed verifier. It does not install or mutate White
Noise. The test APK calls each implementation through the same bridge and first
checks a valid signature, an invalid signature, a valid signed event, and a
mutated event. A failure aborts measurement.

Each operation gets 10 untimed warmups and 20 timed samples of 8 operations.
Each sample records monotonic elapsed nanoseconds divided by 8. The reported
median is the midpoint of the two central samples; throughput is
`1,000,000,000 / medianNs`. Signature-only calls verify Bitcoin BIP-340 vector
0; full-event calls recompute a canonical Nostr event ID before verification.
Both implementations run in the same optimized APK, on the same device and
corpus. Quartz is not compared because White Noise does not pin or ship it.

Build both APKs with `./gradlew :cryptoBenchmark:assembleRelease
:cryptoBenchmark:assembleReleaseAndroidTest`; then run
`:cryptoBenchmark:connectedReleaseAndroidTest` on a physical Android device.
The shared fixture requires its guarded device session and install wrappers.
Save the `bench_json` instrumentation status, target/test APK SHA-256 values,
device fingerprint, ABI, battery and thermal state for every published run.
Emulator runs are correctness smoke tests, not publishable performance numbers.

The [2026-09-23 Pixel 6a raw samples](performance-data/bip340-2026-09-23-pixel6a.json)
come from one guarded physical-device run on Android 17 / arm64-v8a. Battery
was 80% before and after; thermal status was 0 before and after.

| Operation | Legacy median | libsecp256k1 median | Legacy ops/s | libsecp256k1 ops/s |
| --- | ---: | ---: | ---: | ---: |
| Signature only | 233.373 ms | 0.213 ms | 4.28 | 4,693 |
| Canonical full event | 234.544 ms | 0.325 ms | 4.26 | 3,081 |

These are measurements of the two verifier paths, not end-to-end message or
card rendering latency. They do not reproduce the earlier unaccompanied
56-events/second report, which used an unspecified device and setup.

# Native event verifier comparison

`cryptoBenchmark` is an isolated minified Android app. It compiles the production
`NostrEvent.kt` and `NostrEventVerifier.kt` adapter and loads the exact MarmotKit
artifact pinned by the app. No account, Android Keyring, or SQLCipher initialization
runs in this APK. MDK owns canonical event-ID and Schnorr verification.

The benchmark compares full-event verification against a benchmark-only copy of
the removed Kotlin verifier. Both workloads include serialization and event-ID
verification. Before measurement it verifies the signed fixture and rejects
mutated IDs, public keys, signatures, timestamps, kinds, tags, and content. It
also rejects a content mutation with a recomputed ID and the original signature.
The replacement has no signature-only binding or additional secp256k1 JNI library.

Each operation gets 10 untimed warmups and 20 timed samples of 8 operations.
Each sample retains monotonic nanoseconds per operation. The median is the
midpoint of the two central samples; throughput is `1,000,000,000 / medianNs`.
First-call native loading is excluded from the warmed timings.

## Running native verifier tests

Build the isolated minified APKs with
`./gradlew :cryptoBenchmark:assembleRelease :cryptoBenchmark:assembleReleaseAndroidTest`.
Run correctness with
`./gradlew :cryptoBenchmark:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.core.nostr.NostrEventVerifierInstrumentedTest`.
PR and master CI run these correctness cases. For a manual physical-device timing
comparison, select `dev.ipf.whitenoise.android.core.nostr.Bip340PhysicalBenchmark`
instead. Use the shared fixture's guarded session and install wrappers, and retain
`bench_json`, source revision, pinned artifact and APK hashes, ABI, fingerprint,
battery and thermal state. Emulator timings are not performance evidence.

JVM fuzzing covers the parser and serialization roundtrip; cryptographic rejection
runs in this isolated Android APK using the real native library. Domain unit tests
inject immutable verifier decisions and do not claim cryptographic validation.

## Recorded MDK adoption measurements

Recorded physical-device samples and artifact provenance are in the
[2026-10-01 Pixel 6a report](performance-data/bip340-mdk-2026-10-01-pixel6a.json).

| Full-event verifier | Median | Operations per second |
| --- | ---: | ---: |
| Legacy Kotlin | 234.421 ms | 4.27 |
| MDK 0.11.0 | 0.474 ms | 2,112 |

The warmed measurements include canonical event-ID validation and exclude
first-call native loading. They do not measure card rendering or message latency.

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

Build `:cryptoBenchmark:assembleRelease :cryptoBenchmark:assembleReleaseAndroidTest`
and run `:cryptoBenchmark:connectedReleaseAndroidTest`. On the shared physical
fixture use its guarded session and install wrappers. Retain `bench_json`, exact
source revision, MarmotKit artifact SHA-256, APK hashes, ABI, build fingerprint,
battery and thermal state. Emulator results establish correctness only.

JVM fuzzing covers the parser and serialization roundtrip; cryptographic rejection
runs in this isolated Android APK using the real native library. Domain unit tests
inject immutable verifier decisions and do not claim cryptographic validation.

## Recorded MDK adoption measurements

The [2026-10-01 Pixel 6a samples](performance-data/bip340-mdk-2026-10-01-pixel6a.json)
come from one guarded physical-device run of both minified release APKs on
GrapheneOS Android 17 / arm64-v8a. All seven instrumentation tests passed:
the warmed comparison plus six real-native correctness tests. Battery was 80%
and thermal status was 0 before and after measurement.

| Full-event verifier | Median | Operations per second |
| --- | ---: | ---: |
| Legacy Kotlin | 234.421 ms | 4.27 |
| MDK 0.11.0 | 0.474 ms | 2,112 |

The warmed MDK path was approximately 495 times faster for this signed fixture.
The measurements include canonical event-ID validation and exclude first-call
native loading. They do not measure card rendering or message latency.

Both APKs were built from committed benchmark/build inputs at
`3db293d4e3e6cbbc3d6671436ebdc3105bc821f2`. The report records all 24 build-input
hashes, the pinned MarmotKit archive checksum, all 12 prepared artifact-file
hashes, both APK hashes and all packaged native-library hashes. The four ABI
copies of `libmarmot_uniffi.so` match the prepared pinned artifact byte for byte.
The text-event fixture's canonical ID and signature were independently verified
with libsecp256k1; its public test scalar is 1. The earlier parsing fixture had a
stored ID inconsistent with its fields and was correctly rejected by MDK.

## Historical September 30 MDK measurements

The [2026-09-30 Pixel 6a samples](performance-data/bip340-mdk-2026-09-30-pixel6a.json)
come from one guarded physical-device run of the minified release benchmark on
GrapheneOS Android 17 / arm64-v8a. The instrumentation verdict was `OK (1 test)`.
Battery was 80% and thermal status was 0 before and after measurement.

| Full-event verifier | Median | Operations per second |
| --- | ---: | ---: |
| Legacy Kotlin | 234.439 ms | 4.27 |
| MDK 0.11.0 | 0.479 ms | 2,087 |

The warmed MDK path was approximately 489 times faster for this signed fixture.
These measurements cover full-event verification, including canonical event-ID
validation, and do not measure card rendering or end-to-end message latency.

The report includes the MarmotKit artifact checksum, both APK checksums, all
benchmark source checksums, and source revision
`85058376027a20a8e9b2a3bc3f3efc4bebb5cb65`. The APKs were built immediately
before that commit; every benchmark source checksum was verified against the
committed files. Embedded APK VCS metadata may retain the preceding head. The
benchmark now uses the app's standard optimized Android R8 defaults so JNA's
runtime structure annotations survive minification.

## Historical ACINQ measurements

The following retained samples measure the earlier ACINQ/libsecp256k1 candidate.
They are not measurements of the current MDK adapter. The original JSON is
preserved unchanged; signature-only results have no current counterpart.

The [2026-09-23 Pixel 6a raw samples](performance-data/bip340-2026-09-23-pixel6a.json)
come from one guarded physical-device run on Android 17 / arm64-v8a. Battery
was 80% before and after; thermal status was 0 before and after.

| Operation | Legacy median | libsecp256k1 median | Legacy ops/s | libsecp256k1 ops/s |
| --- | ---: | ---: | ---: | ---: |
| Signature only | 233.361 ms | 0.212 ms | 4.29 | 4,712 |
| Canonical full event | 234.934 ms | 0.306 ms | 4.26 | 3,266 |

These are measurements of the two verifier paths, not end-to-end message or
card rendering latency. They do not reproduce the earlier unaccompanied
56-events/second report, which used an unspecified device and setup.

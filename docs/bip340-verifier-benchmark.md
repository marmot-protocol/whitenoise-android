# BIP-340 verifier benchmark

`Bip340VerificationBenchmark` measures both production verification paths on an
Android device:

- `signatureOnlyVerification` calls the app's BIP-340 verifier with Bitcoin
  BIP-340 test vector 0.
- `fullEventVerification` recomputes and compares a canonical Nostr event ID before
  verifying its signature, matching `NostrEventVerifier` in production.

Each operation gets 20 explicit unmeasured warmups, followed by AndroidX
Benchmark's adaptive warmup and measured iterations. The benchmark report's
`timeNs` distribution supplies per-operation latency; calculate throughput as
`1,000,000,000 / median(timeNs)`. Before timing, the tests also check an invalid
signature and a mutated event payload to keep correctness coupled to the
performance measurement.

Use the same physical device, device state, ABI and source-level corpus for both
commits. Run the base commit and the candidate commit with the optimized,
minified `benchmarkRelease` target:

```sh
./gradlew \
  -Pwhitenoise.androidTestBuildType=benchmarkRelease \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.core.nostr.Bip340VerificationBenchmark \
  :app:connectedDevZapstoreBenchmarkReleaseAndroidTest \
  --no-daemon
```

Record the `signatureOnlyVerification` and `fullEventVerification` results from
each run together with the commit, device build fingerprint and ABI. The
build-type property intentionally selects `benchmarkRelease` for every
instrumented test in this invocation, includes the benchmark-only source set,
and selects AndroidX's benchmark runner with report output enabled. The class
filter keeps execution scoped to this benchmark. The benchmark deliberately
does not compare against Quartz because White Noise does not pin or ship Quartz.

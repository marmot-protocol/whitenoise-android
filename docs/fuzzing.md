# JVM fuzzing for Android parser boundaries

White Noise Android runs bounded JVM fuzzing in the dedicated `:fuzz` Kotlin module. The harness targets pure Kotlin parser boundaries before untrusted strings reach UI or protocol calls. It complements unit tests, Android lint, and CodeQL without an emulator, network access, or real account data.

## Module layout

| Path | Purpose |
|------|---------|
| `fuzz/` | Pure JVM module with Jazzer JUnit 5 integration |
| `fuzz/src/test/resources/**/Inputs/` | Synthetic seed and regression corpora |
| `fuzz/regression-corpus/` | Reviewed minimized reproducers promoted after triage |
| `scripts/fuzz-triage.sh` | Privacy-safe crash triage workflow |
| `scripts/fuzz-run-campaign.sh` | Standalone Jazzer launcher per target (`-jobs=2`, `-workers=2`) |

Production parsers are included from `app/` via an exact allow-list in `fuzz/build.gradle.kts`. Do not duplicate parser logic in `:fuzz`.

### JVM targets

Each fuzz class exposes one `@FuzzTest` entry point that dispatches on the final input byte to a bounded subtarget. This matches Jazzer's end-consuming `FuzzedDataProvider` behavior and prevents Jazzer from silently skipping additional `@FuzzTest` methods in the same class.

| Gradle task | Entry point | Subtargets |
|-------------|-------------|------------|
| `:fuzz:fuzzZapstoreProtocol` | `fuzzZapstoreProtocol` | `NostrEventJson`, `RelayEnvelopeFrames`, `RelayEnvelopeSequence` |
| `:fuzz:fuzzIdentityReference` | `fuzzIdentityReference` | `ProfileLink`, `RecipientNormalize`, `RecipientTokenize`, `PlausibleClipboard` |
| `:fuzz:fuzzGroupSystemEvent` | `fuzzGroupSystemEvent` | `Json` |
| `:fuzz:fuzzNip55SignerProtocol` | `fuzzNip55SignerProtocol` | `ParseContentRow`, `ParseActivityResult`, `SignedEventPubkeyHelpers` |

Synthetic seeds live under `*FuzzTestInputs/<entry-point>/` with the fuzz payload followed by a trailing subtarget-id byte (0-based enum ordinal).

Bounds enforced in harness helpers and Jazzer engine settings: 64 KiB strings (`jazzer.max_len=65536`), 64 collection elements, depth 16, 32 relay frames.

Grammar dictionaries: inline `@DictionaryEntries` plus `fuzz/src/test/resources/fuzz-grammar.dict` attached with `@DictionaryFile` on each `@FuzzTest` entry point.

## Local commands

```bash
export JAVA_HOME=/path/to/temurin-17

# Compile fuzz sources
./gradlew :fuzz:compileKotlin :fuzz:compileTestKotlin

# Deterministic :fuzz and named app-suite corpus replay (PR gate)
./gradlew :fuzz:replayAllFuzzRegression

# Bounded campaign per target (standalone Jazzer with jobs=2, workers=2)
./gradlew :fuzz:fuzzZapstoreProtocol :fuzz:fuzzIdentityReference :fuzz:fuzzGroupSystemEvent :fuzz:fuzzNip55SignerProtocol

# All targets sequentially (scheduled CI style)
./gradlew :fuzz:fuzzScheduledDryRun

# Runner self-check (verifies standalone Jazzer jobs/workers in engine logs)
scripts/fuzz-run-campaign.sh --self-check

# Injected campaign-failure self-check (minimize -> fresh replay -> privacy/classification metadata)
scripts/fuzz-run-campaign.sh --self-check-failure

# Fixed-run local verification
./gradlew :fuzz:fuzzZapstoreProtocol -PfuzzRuns=10000 -PfuzzMaxHeap=2g

# Android-native media seam (run on an emulator; never uninstall a personal-device app)
./gradlew :app:connectedDevZapstoreDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.media.MediaReferenceSupportFuzzIntegrationTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Passing `-PfuzzRuns=<N>` sets libFuzzer `-runs=<N>` per target. Scheduled CI omits `-PfuzzRuns` and uses `-max_total_time` (`90s` nightly, `10m` weekly manual dispatch).

## Concurrency model

Campaigns invoke `com.code_intelligence.jazzer.Jazzer` directly via Gradle `JavaExec` tasks. Each JVM target runs sequentially through `scripts/fuzz-run-campaign.sh`, which:

1. Precompiles `:fuzz` once
2. Runs one standalone Jazzer process per target with `-jobs=2`, `-workers=2`, `-max_len=65536`, and `-Xmx2g`
3. Writes full engine output to `fuzz/build/fuzz-campaign-logs/` (gitignored) and emits sanitized status lines only

`fuzz/build/fuzz-engine-metadata.properties` records:

| Field | Meaning |
|-------|---------|
| `jobs_requested` / `workers_requested` | libFuzzer parallelism flags passed to standalone Jazzer |
| `jobs_applied` / `workers_applied` | Same values (`-jobs=2`, `-workers=2`) |

Campaign logs under `fuzz/build/fuzz-campaign-logs/` show the Jazzer wrapper launching two libFuzzer jobs (`>fuzz-0.log`, `>fuzz-1.log`, `Job 0 exited`, `Job 1 exited`).

## CI

| Workflow | Trigger | Limit |
|----------|---------|-------|
| `.github/workflows/fuzz-pr.yml` | PR touching harness or parser targets | 5 minutes, compile + `:fuzz` and eight named app-suite corpus replays |
| `.github/workflows/fuzz-scheduled.yml` | Nightly `master` + `workflow_dispatch` | 15 minutes total (nightly) or 60 minutes (weekly manual) |

Scheduled runs call `:fuzz:fuzzScheduledDryRun` (the shell runner), execute one target at a time, use `-Xmx2g` per worker JVM, `contents: read`, no secrets, and workflow-level concurrency with `cancel-in-progress: true`. Engine input size is capped at 64 KiB via `-max_len=65536`. A failed target stops the campaign immediately; the first deterministic artifact is minimized, replayed, and classified before the campaign exits non-zero. The four 90-second nightly target budgets leave 3.5 minutes of the 15-minute ceiling for setup, compilation, metadata, and artifact upload after the bounded 5.5-minute triage path.

Artifacts retain reviewed minimized reproducers under `fuzz/regression-corpus/`, `fuzz/build/fuzz-engine-metadata.properties`, and digest-only sanitized triage metadata under `fuzz/build/fuzz-triage-metadata/` for 7 days. Evolving corpora (`fuzz/build/cifuzz-corpus/`), legacy JUnit corpus dirs (`fuzz/.cifuzz-corpus/`), Gradle campaign logs (`fuzz/build/fuzz-campaign-logs/`), local minimized review files, and unreviewed crash payloads are never uploaded.

## Crash triage

Run `scripts/fuzz-triage.sh` after a local fuzz crash:

```bash
scripts/fuzz-triage.sh :fuzz:fuzzIdentityReference path/to/reproducer [RecipientNormalize]
```

The script:

1. Preserves the standalone Jazzer artifact byte-for-byte and derives its subtarget from the final byte; an optional subtarget name validates that mapping
2. Minimizes the crash with standalone Jazzer's libFuzzer minimization mode
3. Replays the minimized artifact in a fresh JVM via `:fuzz:replayFuzzRegression`
4. Privacy-checks the minimized artifact (digest only in status output; no payload dump), emits private-by-default classification guidance, and writes sanitized metadata for scheduled retention

Manual follow-up before retention:

1. Add a deterministic `app` unit test covering the finding
2. Copy the privacy-reviewed minimized input into `fuzz/regression-corpus/<target>/`
3. Re-run `./gradlew :fuzz:replayAllFuzzRegression`

Uploaded triage metadata contains only target/subtarget names, digests, engine version, elapsed time, stage/status, and sanitized exception class. Full Jazzer engine logs stay local under the gitignored build directory.

### Sensitive findings

Potential signature-bypass, parser-confusion, memory/CPU denial-of-service, privacy, or cross-account issues must use GitHub private vulnerability reporting. Do not paste exploit inputs, stack traces, keys, or message content into public issues or PRs.

## Dependency pins

- `com.code-intelligence:jazzer-junit:0.30.0` (via `gradle/libs.versions.toml`)
- `org.junit.jupiter:junit-jupiter:5.11.4`

## Native integration boundary

`MediaReferenceSupportFuzzIntegrationTest` runs 10,000 deterministic, bounded synthetic tag-list iterations on Android. It derives each expected accept/reject verdict by invoking the pinned MarmotKit parser directly, then compares that ordered result with `MediaReferenceSupport.parseAllImetaTags`. Only `MarmotKitException` is treated as a rejected tag; linkage failures escape, so a missing native library cannot be mistaken for parser coverage. The harness performs no network, account, relay, signer, credential, database, or attachment I/O.

- **Android-owned parser seams** — [White Noise Android #2117](https://github.com/marmot-protocol/whitenoise-android/issues/2117) owns `GroupSystemEventJson` JVM fuzzing and the MarmotKit-backed Android media integration harness. MarmotKit/Android dependencies remain outside the pure `:fuzz` module, and native validation is not duplicated in Kotlin.
- **Upstream protocol/native boundaries** — [Marmot MDK #1476](https://github.com/marmot-protocol/mdk/issues/1476) tracks bounded native fuzzing for Nostr peeling, MLS ingestion, Marmot media/event parsing, and UniFFI conversions.
- White Noise Android #1580/#1581 and MDK #955/#956 cover parser migration, FFI APIs, or authenticated provenance. They are related implementation work, not substitutes for the linked phase-2 fuzzing issues.

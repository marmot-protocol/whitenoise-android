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

### Phase-1 targets

Each fuzz class exposes one `@FuzzTest` entry point that dispatches on the first input byte to a bounded subtarget. This prevents Jazzer from silently skipping additional `@FuzzTest` methods in the same class.

| Gradle task | Entry point | Subtargets |
|-------------|-------------|------------|
| `:fuzz:fuzzZapstoreProtocol` | `fuzzZapstoreProtocol` | `NostrEventJson`, `RelayEnvelopeFrames`, `RelayEnvelopeSequence` |
| `:fuzz:fuzzIdentityReference` | `fuzzIdentityReference` | `ProfileLink`, `RecipientNormalize`, `RecipientTokenize`, `PlausibleClipboard` |
| `:fuzz:fuzzNip55SignerProtocol` | `fuzzNip55SignerProtocol` | `ParseContentRow`, `ParseActivityResult`, `SignedEventPubkeyHelpers`, `IntentFallbackBudget` |

Synthetic seeds live under `*FuzzTestInputs/<entry-point>/` with a leading subtarget-id byte (0-based enum ordinal) followed by the fuzz payload.

Bounds enforced in harness helpers and Jazzer engine settings: 64 KiB strings (`jazzer.max_len=65536`), 64 collection elements, depth 16, 32 relay frames.

Grammar dictionaries: inline `@DictionaryEntries` plus `fuzz/src/test/resources/fuzz-grammar.dict` attached with `@DictionaryFile` on each `@FuzzTest` entry point.

## Local commands

```bash
export JAVA_HOME=/path/to/temurin-17

# Compile fuzz sources
./gradlew :fuzz:compileKotlin :fuzz:compileTestKotlin

# Deterministic regression replay (PR gate)
./gradlew :fuzz:replayFuzzRegression

# Bounded campaign per target (standalone Jazzer with jobs=2, workers=2)
./gradlew :fuzz:fuzzZapstoreProtocol :fuzz:fuzzIdentityReference :fuzz:fuzzNip55SignerProtocol

# All targets sequentially (scheduled CI style)
./gradlew :fuzz:fuzzScheduledDryRun

# Runner self-check (verifies standalone Jazzer jobs/workers in engine logs)
scripts/fuzz-run-campaign.sh --self-check

# Fixed-run local verification
./gradlew :fuzz:fuzzZapstoreProtocol -PfuzzRuns=10000 -PfuzzMaxHeap=2g
```

Passing `-PfuzzRuns=<N>` sets libFuzzer `-runs=<N>` per target. Scheduled CI omits `-PfuzzRuns` and uses `-max_total_time` (`3m` nightly, `10m` weekly manual dispatch).

## Concurrency model

Campaigns invoke `com.code_intelligence.jazzer.Jazzer` directly via Gradle `JavaExec` tasks. Each phase-1 target runs sequentially through `scripts/fuzz-run-campaign.sh`, which:

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
| `.github/workflows/fuzz-pr.yml` | PR touching harness or parser targets | 5 minutes, compile + regression replay only |
| `.github/workflows/fuzz-scheduled.yml` | Nightly `master` + `workflow_dispatch` | 15 minutes total (nightly) or 60 minutes (weekly manual) |

Scheduled runs call `:fuzz:fuzzScheduledDryRun` (the shell runner), execute one target at a time, use `-Xmx2g` per worker JVM, `contents: read`, no secrets, and workflow-level concurrency with `cancel-in-progress: true`. Engine input size is capped at 64 KiB via `-max_len=65536`.

Artifacts retain reviewed minimized reproducers under `fuzz/regression-corpus/` and `fuzz/build/fuzz-engine-metadata.properties` for 7 days. Evolving corpora (`fuzz/build/cifuzz-corpus/`), legacy JUnit corpus dirs (`fuzz/.cifuzz-corpus/`), Gradle campaign logs (`fuzz/build/fuzz-campaign-logs/`), and unreviewed crash payloads are never uploaded.

## Crash triage

Run `scripts/fuzz-triage.sh` after a local fuzz crash:

```bash
scripts/fuzz-triage.sh :fuzz:fuzzIdentityReference path/to/reproducer RecipientNormalize
```

The script:

1. Validates the Gradle task and subtarget name
2. Privacy-checks content (digest only in logs; no payload dump)
3. Replays the prefixed reproducer in a fresh JVM via `:fuzz:replayFuzzRegression`
4. Classifies as public regression vs. sensitive security finding

Manual follow-up before retention:

1. Minimize with `JAZZER_FUZZ=1` and libFuzzer minimization on the crashing subtarget
2. Add a deterministic `app` unit test covering the finding
3. Copy the reviewed input (with subtarget prefix byte) into `fuzz/regression-corpus/<entry-point>/`
4. Re-run `./gradlew :fuzz:replayFuzzRegression`

Logs contain only target name, seed digest, engine version, elapsed time, and sanitized exception class.

### Sensitive findings

Potential signature-bypass, parser-confusion, memory/CPU denial-of-service, privacy, or cross-account issues must use GitHub private vulnerability reporting. Do not paste exploit inputs, stack traces, keys, or message content into public issues or PRs.

## Dependency pins

- `com.code-intelligence:jazzer-junit:0.30.0` (via `gradle/libs.versions.toml`)
- `org.junit.jupiter:junit-jupiter:5.11.4`

## Phase-2 follow-up (not in this module)

- **GroupSystemEvents.parse** — `GroupSystemEvents.kt` couples JSON fallback parsing to MarmotKit FFI types (`GroupSystemEventFfi`, `TimelineMessageRecordFfi`) and `ProfileSanitizer`. Fuzzing belongs here only after extracting a JVM-safe `parse(plaintext)` seam or adding a pure helper file; do not pull MarmotKit/Android into `:fuzz`. Open follow-up: [White Noise Android #1581](https://github.com/marmot-protocol/whitenoise-android/issues/1581) and [Marmot MDK #956](https://github.com/marmot-protocol/mdk/issues/956).
- **MarmotKit `MediaReferenceSupport.parseAllImetaTags` integration harness** — completed media FFI follow-up tracked in [White Noise Android #1580](https://github.com/marmot-protocol/whitenoise-android/issues/1580) and [Marmot MDK #955](https://github.com/marmot-protocol/mdk/issues/955).
- Upstream MarmotKit/MDK native FFI fuzzing (separate repository issue)

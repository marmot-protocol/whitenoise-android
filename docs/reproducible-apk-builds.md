# Reproducible APK builds (#1261)

This document describes what the repository verifies today for release APK
reproducibility and what remains out of scope.

## What is verified

`scripts/repro-verify.sh` checks **packaging determinism** for the **unsigned**
**production** + **Zapstore** + **arm64-v8a** + **release** variant:

- Two isolated git checkouts of the **same commit** (different paths on disk).
- A minimal build environment with fixed locale/time zone plus isolated `HOME`
  and Gradle state; local Gradle settings and app runtime variables are not
  inherited.
- A separate `GRADLE_USER_HOME` for each build, so the second build cannot reuse
  dependency, execution, or build-cache state produced by the first.
- No `local.properties`, no `app/google-services.json`, and no release signing
  environment variables (defaults / empty runtime config only).
- The exact MarmotKit Android archive is pinned by immutable URL, source SHA,
  and SHA-256. Each isolated build verifies and extracts that same archive
  through Gradle before compiling its Kotlin and JNI payload.
- The two resulting APK files must be **byte-identical** on that verifier host.
- Each APK must be **unsigned** (`apksigner verify` must exit 1 with output
  beginning `DOES NOT VERIFY`).

CI workflow [`.github/workflows/android-repro-verify.yml`](../.github/workflows/android-repro-verify.yml)
runs the same script on tag pushes, every pull request to `master`, and manual dispatch.
Tag/manual runs publish the verified unsigned APK, `SHA256SUMS`, and a toolchain
report as workflow artifacts; pull requests verify without publishing an APK.
The unsigned artifact is same-run comparison evidence for the workflow run (90-day
artifact retention); it is not a durable release distribution asset.

## What is not verified

- **Signed APKs** — signing keys and v2/v3 signature blocks are not stable
  byte-for-byte targets. Distribution builds should be validated with certificate
  / signature checks, not `cmp` against CI bytes.
- **Rebuilding MarmotKit from Rust source** — this verifier consumes the
  immutable release archive and verifies its MDK source identity and checksum;
  reproducing the archive from its Rust/NDK toolchain remains an upstream MDK
  concern.
- **Staging / dev flavors**, other ABIs, Play distribution, or PR preview APKs.
- **Hermetic cross-toolchain reproducibility.** The workflow pins Ubuntu 24.04 and
  exact Temurin **21.0.7+6.0.LTS** as `JAVA_HOME`. The verifier runs Gradle with
  `--no-daemon`, restricts toolchain discovery in an isolated `GRADLE_USER_HOME`
  (`org.gradle.java.installations.paths` plus auto-detect/auto-download disabled),
  and records each Gradle build JVM’s actual home, vendor, version, and runtime
  version from a generated init script. Those properties must match the configured
  `JAVA_HOME` JVM and match between both builds (the
  `gradle/gradle-daemon-jvm.properties` criteria remain unchanged). This report
  covers the JVM executing Gradle, not separate compiler or worker processes.
  The Gradle wrapper checksum and repository dependency versions are pinned, and CI
  records installed Android SDK packages in `repro-build-environment.txt` before
  the verifier cleans up its temporary Gradle state. Android SDK packages and Maven
  Maven dependencies are not yet pinned by repository-owned content hashes.
  MarmotKit is pinned separately by the repository-owned lock and archive SHA-256.
  The two-build check therefore proves path/build-order determinism on one verifier
  host with that pinned JVM; cross-host matching still requires reconciling the
  recorded toolchain inputs.
- **Gradle archive knobs** such as `preserveFileTimestamps` / `reproducibleFileOrder`
  on `AbstractArchiveTask` — Android APK packaging does not use that path; they
  would not change APK output here.
- **`SOURCE_DATE_EPOCH`** — AGP does not apply it automatically for APK
  packaging; setting it alone would not change outputs without additional wiring.

## Third-party verification from a tag

Check out the tag and reproduce the environment recorded in the workflow's
`repro-build-environment.txt` artifact. CI uses Ubuntu 24.04, exact Temurin
21.0.7+6.0.LTS as `JAVA_HOME`, records each build’s actual JVM properties in the
toolchain report, and lists the apksigner path used for unsigned checks; the report
also lists installed Android SDK package revisions and wrapper checksums. The script
currently requires Linux with Bash and GNU coreutils/findutils. Then run:

```bash
git checkout <tag>
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
./scripts/repro-verify.sh --ref HEAD
```

On success the script prints `repro-verify: OK` and a `sha256=…` line. Compare
that digest to the `SHA256SUMS` file attached to the matching
**Android Reproducible Release Verify** workflow run for the tag. A mismatch on a
different host first means the toolchain inputs must be reconciled; it is not by
itself evidence of source tampering.

To compare against the artifact file directly:

```bash
sha256sum -c SHA256SUMS
```

## Local unsigned smoke build (single tree)

For a fast local compile smoke test only:

```bash
export WHITENOISE_ALLOW_UNSIGNED_RELEASE=true
export GITHUB_SHA="$(git rev-parse HEAD)"
./gradlew :app:assembleProductionZapstoreRelease \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false
```

This is not equivalent to reproducibility verification. It omits the sanitized
environment, separate `GRADLE_USER_HOME` values, `--no-daemon`, the JVM-report
init script and property, removal of local signing inputs, the second build,
unsignedness checks, and byte comparison. Use `scripts/repro-verify.sh` for the
actual invariant.

Unsigned release APKs may land under
`app/build/intermediates/apk/productionZapstore/release/` rather than
`outputs/apk/` when signing is not configured.

## Release signing guard

Normal signed release workflows are unchanged. Release packaging still **fails**
without signing credentials unless `WHITENOISE_ALLOW_UNSIGNED_RELEASE=true` is set
explicitly (as the verifier does internally).

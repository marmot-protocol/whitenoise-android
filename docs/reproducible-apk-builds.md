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
- No `local.properties`, no `app/google-services.json`, and no release signing
  environment variables (defaults / empty runtime config only).
- Checked-in JNI libraries and Kotlin bindings are compiled into the APK as-is.
- The two resulting APK files must be **byte-identical** on that verifier host.

CI workflow [`.github/workflows/android-repro-verify.yml`](../.github/workflows/android-repro-verify.yml)
runs the same script on tag pushes, relevant pull requests, and manual dispatch.
Tag/manual runs publish the verified unsigned APK, `SHA256SUMS`, and a toolchain
report as workflow artifacts; pull requests verify without publishing an APK.
The unsigned artifact is comparison evidence, not an installable release.

## What is not verified

- **Signed APKs** — signing keys and v2/v3 signature blocks are not stable
  byte-for-byte targets. Distribution builds should be validated with certificate
  / signature checks, not `cmp` against CI bytes.
- **JNI / MDK provenance** — reproducibility of how `libmarmot_uniffi.so` was
  built is tracked separately in [marmot-protocol/mdk#814](https://github.com/marmot-protocol/mdk/issues/814).
  This repo only pins whatever `.so` bytes are committed.
- **Staging / dev flavors**, other ABIs, Play distribution, or PR preview APKs.
- **Hermetic cross-toolchain reproducibility.** The workflow pins Ubuntu 24.04,
  Temurin 17.0.19+10 as the launcher JDK; the checked-in Gradle daemon criteria
  select Temurin 21.0.7+6. The Gradle wrapper checksum and repository dependency
  versions are pinned, and CI records installed Android SDK packages. Android
  SDK packages and Maven dependencies are not yet checked in or pinned by
  repository-owned content hashes. The two-build check therefore proves
  path/build-order determinism on one verifier host; cross-host matching still
  requires the recorded toolchain inputs.
- **Gradle archive knobs** such as `preserveFileTimestamps` / `reproducibleFileOrder`
  on `AbstractArchiveTask` — Android APK packaging does not use that path; they
  would not change APK output here.
- **`SOURCE_DATE_EPOCH`** — AGP does not apply it automatically for APK
  packaging; setting it alone would not change outputs without additional wiring.

## Third-party verification from a tag

Check out the tag and reproduce the environment recorded in the workflow's
`repro-build-environment.txt` artifact. CI uses Ubuntu 24.04, exact Temurin
17.0.19+10 as the launcher, and the Temurin 21.0.7+6 daemon selected by
`gradle/gradle-daemon-jvm.properties`; the report lists installed Android SDK
package revisions and wrapper checksums. Then run:

```bash
git checkout <tag>
export JAVA_HOME=/path/to/jdk-17
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

The verifier’s per-tree Gradle invocation is equivalent to:

```bash
export WHITENOISE_ALLOW_UNSIGNED_RELEASE=true
export GITHUB_SHA="$(git rev-parse HEAD)"
./gradlew :app:assembleProductionZapstoreRelease \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false
```

Unsigned release APKs may land under
`app/build/intermediates/apk/productionZapstore/release/` rather than
`outputs/apk/` when signing is not configured.

## Release signing guard

Normal signed release workflows are unchanged. Release packaging still **fails**
without signing credentials unless `WHITENOISE_ALLOW_UNSIGNED_RELEASE=true` is set
explicitly (as the verifier does internally).

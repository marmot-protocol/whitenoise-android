# Android release pipeline

The release pipeline builds a candidate once and distributes those exact bytes.
Building, Play internal testing, GitHub drafts, and public Zapstore publication
are separate actions. No merge, tag push, or successful build publishes a release.

Product delivery and qualification remain tracked in
[#2125](https://github.com/marmot-protocol/whitenoise-android/issues/2125), with
GitHub/artifact work in #2126, Play in #2127, Zapstore in #2128, qualification in
#2184, and final public receipts in #2387.

## Distribution boundaries

| Manual workflow | Effect | Approval boundary |
| --- | --- | --- |
| **Android Production Build** | Build and verify an ARM64 direct APK and four-ABI Play AAB; retain a candidate bundle for 30 days. No GitHub release or store upload. | `android-release-signing` environment |
| **Android Release - GitHub Draft or Play Internal** | Distribute an existing, reviewed bundle. Default destination is a GitHub **draft**. The other destination releases to Play **internal testing only**. | Explicit dispatch; Play also requires `google-play-internal` approval |
| **Android Zapstore - PUBLIC Publication** | Publish the reviewed APK and listing publicly. This has no private/internal track. | Separate dispatch, version-specific typed confirmation, and `zapstore-production` approval |

All workflows run from `master`. Keep required reviewers and the `master` branch
restriction enabled on all three environments. Never give an automatic build or
internal distribution workflow access to the Zapstore signing environment.
Do not add a `workflow_run`, push, or tag trigger to the publication workflow.

## Release identity and versions

`config/android-release.properties` pins the application ID, signing certificate
fingerprints, Zapstore publisher, and checksummed tooling versions:

- Application ID and Zapstore listing: `dev.ipf.whitenoise.android`.
- Direct APK / Play app-signing certificate SHA-256:
  `e3ca27b35a14f3e67d0fa68c270c9a330539ac89930d89e6e5c05beba8f03b7a`.
- Separate Play upload certificate SHA-256:
  `b0e30845fedc49d162198ca577c9ae653e2a66ba06a6ddca2cfccdd180193086`.

The upload key signs the AAB; Google Play signs delivered APKs with its configured
app-signing key. The pipeline validates the two input artifacts' certificates;
verify Play's app-signing configuration and delivered APK separately.

Each new candidate needs a fresh calendar `versionName` and increasing
`versionCode` in `app/build.gradle.kts`. Check codes already used in Play Console
before choosing one. Do not reuse `android-v2026.9.5` for new source: it already
identifies the earlier manual release. Existing tags must resolve to the exact
candidate commit, including annotated tags. A conflicting tag fails preparation
and distribution. The workflow never moves an existing tag.

For a new version, update deterministic Settings version fixtures and affected
Roborazzi baselines, add `fastlane/metadata/android/en-US/changelogs/<code>.txt`,
and update `release_notes` in `zapstore.yaml`. Run `just verify-release-metadata`.
The metadata checker requires Zapstore notes to match the current code.

## Store assets

Zapstore listing text and ordered image references live in `zapstore.yaml`.
Play listing text lives in `fastlane/metadata/android/en-US/` and is maintained
separately. The pipeline does not synchronize or upload Play listing copy.

The five curated phone images live under
`fastlane/metadata/android/en-US/images/phoneScreenshots/`. Preserve those files
as authored. `just store-assets` regenerates only the launcher icon and Play
feature graphic; it never overwrites or recreates phone screenshots.

Validation accepts 1080x1920 RGB PNGs and RGBA PNGs whose alpha channel is fully
opaque. It requires every curated screenshot to be referenced exactly once by
Zapstore. No image conversion is performed by a release build.

The candidate's `store-assets-<version>.zip` includes `zapstore.yaml` and the
entire English metadata directory. Publication uses those archived files,
verified against the source commit, rather than whatever is on master later.
ZSP runs with `--skip-metadata --no-compress` so external metadata cannot replace
reviewed copy and image compression cannot rewrite the reviewed assets.

## Production configuration

The protected `android-release-signing` environment contains:

- `ANDROID_GOOGLE_SERVICES_JSON_BASE64` with the production Firebase client;
- `WHITENOISE_PRODUCTION_KEYSTORE_BASE64`, `WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD`,
  `WHITENOISE_PRODUCTION_KEY_ALIAS`, `WHITENOISE_PRODUCTION_KEY_PASSWORD`;
- `WHITENOISE_PLAY_UPLOAD_KEYSTORE_BASE64`, `WHITENOISE_PLAY_UPLOAD_KEYSTORE_PASSWORD`,
  `WHITENOISE_PLAY_UPLOAD_KEY_ALIAS`, `WHITENOISE_PLAY_UPLOAD_KEY_PASSWORD`.

The workflow reads the existing repository secrets using the same names Gradle
uses: shared `WHITENOISE_OTLP_ENDPOINT`, `WHITENOISE_AUDIT_LOG_ENDPOINT`,
`WHITENOISE_AUDIT_LOG_AUTH_TOKEN`, and `WHITENOISE_PUSH_RELAY_HINT`, plus
flavor-specific `WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN` and
`WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX`. The compatibility tenant remains
fixed in Gradle. Old production-prefixed endpoint/audit variables are unused.

Firebase client configuration, MIP-05 push identity/relay, and opt-in telemetry
are independent systems. Production builds enforce the Firebase/push guards.
Telemetry credentials are ingest-only and compiled into the app; never put a
privileged service credential in a BuildConfig field.

Aptabase product analytics is a separate destination from OTLP and Goggles.
For a release intended to include it, provision the environment-specific
`PRODUCT_EVENTS_ENDPOINT`, `PRODUCT_APP_KEY`, `PRODUCT_OPERATOR`, and
`PRODUCT_RETENTION` fields documented in
[`product-analytics.md`](product-analytics.md). Run
`./gradlew :app:verifyProductionProductAnalyticsConfig` against the resolved
configuration, and check those values in the resulting candidate without
printing keys. The general runtime-completeness flag does not require these
optional product fields. Changing them requires a new build and may require
renewed consent; configured artifacts alone do not prove Aptabase ingestion.

Audit upload credentials are installed only after the user accepts the current
**Share technical logs** disclosure. The native recording preference alone is
not permission to upload. An upgrade with an older enabled recording choice
clears upload authorization and disables recording before native startup,
including background startup; the app then offers a fresh choice on Chats.
Usage/Aptabase/OTLP consent is independent. Build flags must never opt a user in.
For a consent-change candidate, qualify SEC-008 and SEC-012 using both a fresh
installation and an upgrade with the old recording choice enabled. Cancel must
leave uploads off; disabling sharing must stop new uploads without claiming to
recall requests already in flight or delete previously stored logs.

`google-play-internal` contains `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. Give that
account testing-track permissions only. The workflow hardcodes `internal`;
there is no input for production, open testing, or a rollout percentage.

`zapstore-production` contains a scoped `ZAPSTORE_SIGN_WITH` bunker URI and a
stable `ZAPSTORE_BUNKER_CLIENT_KEY` (64 hex characters). The runner restores the
client identity before connecting. The bunker address identifies the remote
signer transport and need not equal the publishing key. A signed preflight
checks the returned event author against `ZAPSTORE_PUBLISHER_PUBKEY` before the
online publication command. Direct nsec signing is not supported by this CI path.

`bash scripts/test-zsp-offline.sh` qualifies the checksummed real ZSP binary
with a synthetic APK, disposable signing keys, and a loopback-only NIP-46 signer.
It checks the version, JSONL output, event signatures/authors and kinds
32267/30063/3063, and restoration of the preseeded client identity. No production
credentials are read, and no online publication command is run. It requires Go,
JDK 17+, Android SDK platform 36 and build-tools 36.0.0. CI runs it in a separate
job and retains `zsp-offline-contract` as evidence; local receipts default to
`build/reports/zsp-offline.json`. Record qualification results in #2128.

ZSP uses the platform config directory: on the Linux publication runner the key
is `$XDG_CONFIG_HOME/zsp/bunker-keys/<transport-pubkey>.key`; on macOS it is under
`~/Library/Application Support/zsp/bunker-keys/`. The rehearsal creates and removes
only its own randomly named test-key file. Offline mode can contact its signer.
The real production signer connection still needs holder qualification before
public use; the synthetic rehearsal does not establish access to that signer.

First-release Android signing-certificate linking, where needed by Zapstore,
is a separate publisher-operated prerequisite. CI uses `--skip-certificate-linking` and does
not load the app-signing keystore into the publication job.

## Build and review

1. Merge the reviewed pipeline/version/listing changes with required CI green.
2. Dispatch **Android Production Build** on master with the exact versionName.
3. Approve the signing environment. The build checks configuration, builds both
   variants, checks APK package/version/signer/ABI and Play bundle validity,
   package/version/upload signer/four ABIs, and requires separate Play and Zapstore
   R8 mappings for crash retracing. Both mappings are retained in the reviewed bundle.
4. Download the candidate artifact. Review its manifest, checksums, release
   notes, listing archive, APK and AAB; exercise the manual release checklist on
   authorized test devices. Compare the manifest source with the intended commit.
5. Record the **build run ID** and **manifest SHA-256** shown in the build summary.
   Retain the artifact for qualification. An expired artifact cannot be promoted;
   a new build is a new candidate requiring another review.

### Operator commands: build to Play internal

Run these from the repository with authenticated `gh`, Python 3, and Git.
First inspect `git status --short` and preserve any existing work. Complete the
version changes above and their required CI before dispatching. Full PR CI may
run automatically for a version change; distribution itself does not repeat
compilation or the Android test suite.

```bash
gh auth status
git fetch origin master
release_version='REPLACE_WITH_VERSION'
gh workflow run android-production-release.yml --ref master \
  -f expected_version="$release_version"
```

Use the run URL returned by `gh` to record its numeric ID. Review its source SHA
against the intended `origin/master`, approve `android-release-signing` through
the authorized reviewer, and wait for success. Do not pick a candidate solely
because it is the latest run; another operator may also be building.

```bash
build_run_id='REPLACE_WITH_BUILD_RUN_ID'
gh run view "$build_run_id" --json headSha,status,conclusion,url
gh run watch "$build_run_id" --exit-status
```

Read the manifest digest from that successful run's summary. Fetch into a fresh
directory; this command verifies the workflow/run/attempt, source, policy,
payload hashes, and archived metadata against the exact source commit.

```bash
manifest_sha256='REPLACE_WITH_REVIEWED_MANIFEST_SHA256'
candidate_dir="build/releases/$release_version/pipeline-$build_run_id"
python3 scripts/release_bundle.py fetch \
  --run-id "$build_run_id" --version "$release_version" \
  --manifest-sha256 "$manifest_sha256" --directory "$candidate_dir"
```

Review the files and complete the authorized device qualification in
[`manual-release-testing.md`](manual-release-testing.md). For a manual download
release, hand off `whitenoise-android-<version>-arm64-v8a.apk` from this directory
with its SHA-256 and source commit. The AAB is a separate Play artifact from the
same candidate run. Do not substitute a local rebuild under the same filename.

Only when internal distribution is requested, dispatch with an explicit
destination (the workflow's default is a GitHub draft):

```bash
gh workflow run android-release-distribute.yml --ref master \
  -f build_run_id="$build_run_id" -f expected_version="$release_version" \
  -f manifest_sha256="$manifest_sha256" -f destination=play-internal
```

Record this separate distribution run ID, approve `google-play-internal` through
the authorized reviewer, and wait for success. In Play Console, confirm the
internal release's version code/name, **Available to internal testers** status,
and attached mapping file; confirm the production track is unchanged. Retain
the build/distribution URLs, run attempts, manifest digest, artifact hashes, and
Console readback with the release evidence. Keep receipts outside the candidate
directory: its verified file inventory must remain exact.

The production workflow checks both keystore certificates against the pinned
fingerprints before compiling. A mismatch reports only public certificate
fingerprints; passwords remain environment inputs. The finished APK and AAB
still undergo their separate signature checks before artifact upload. APK verification
recognizes numbered, scheme-prefixed, and SDK-range signer labels;
every reported APK signing certificate must match the pinned identity. Source
stamp certificates do not count as APK signing certificates.

For an independent APK check, use Android SDK tools, not a raw ZIP-entry or
binary-manifest string scan. With `apksigner` and `aapt` on PATH:

```bash
python3 scripts/verify_apk_signature.py "$(command -v apksigner)" \
  "$candidate_dir/whitenoise-android-$release_version-arm64-v8a.apk" \
  "$(python3 scripts/release_bundle.py property APP_SIGNING_SHA256)"
aapt dump badging "$candidate_dir/whitenoise-android-$release_version-arm64-v8a.apk"
aapt dump xmltree "$candidate_dir/whitenoise-android-$release_version-arm64-v8a.apk" AndroidManifest.xml
bash scripts/verify-play-bundle-signature.sh \
  "$candidate_dir/whitenoise-android-$release_version-play.aab" \
  "$(python3 scripts/release_bundle.py property PLAY_UPLOAD_SHA256)"
```

Check the decoded APK package, version, ARM64-only native code, and that
`debuggable` and `testOnly` are false (absent attributes default to false).
APK v2 signatures and certificates reside in the APK Signing Block, outside
ordinary ZIP entries; absence of `.RSA`, `.EC`, or `.DSA` entries is not evidence
of an unsigned APK. `META-INF/AL2.0` is Apache license text, not a signature marker.
See the [Android v2 format documentation](https://source.android.com/docs/security/features/apksigning/v2).
After transferring an APK, compare its SHA-256 at both ends before publication.

`release-manifest.json` binds the source, version, signing fingerprints, build
run/attempt, runtime completeness, and file hashes. This is a verified inventory,
not a cryptographic reproducible-build attestation or proof of device behavior.

Local `just production-release <version>` always builds fresh outputs.
It invokes the Play AAB build with `-Pwhitenoise.playBundle=true`. This explicit
mode selects the upload key and disables APK splits; resolved release APK tasks
are rejected, and the production Zapstore variant is disabled. Bundle requests
without the flag fail with a configuration hint, including abbreviated tasks.
`clean` may accompany a bundle request. `python3 scripts/test-release-gradle.py`
checks key selection and these task boundaries without building or signing.
`--allow-dirty` and `--allow-incomplete-runtime` mark local rehearsal bundles as
non-publishable. There is no `--skip-build` option. Distribution accepts only a
successful manual master build from the production workflow in this repository,
with the exact run attempt and reviewed manifest digest. It rejects missing,
changed, additional, or symlinked payloads and listing/source differences.

## GitHub drafts and Play internal testing

Dispatch **Android Release - GitHub Draft or Play Internal** with the reviewed
build run ID, version, and manifest SHA-256. Select `github-draft` or
`play-internal`. No rebuild or signing occurs in this workflow.

GitHub remains draft, with only the direct APK, the reviewed release manifest,
and checksums for those public files attached. Play bundles, crash mappings, and
listing archives remain in the complete CI candidate bundle.
Resuming a partial draft is allowed only when its target
and every existing asset match the candidate. Published releases and differing
candidate bytes are never replaced. Publishing the draft publicly remains a
separate deliberate action after qualification.

Play uploads the reviewed AAB and mapping, with release notes, to internal
testing as a completed internal release. Testers may receive it immediately.
Verify the actual Play track/version and delivered signing lineage in Console.
If upload fails or a version code was already used, inspect Play before retrying;
this workflow does not automatically allocate a new code or promote any track.

If the log says the AAB uploaded but committing the edit failed (for example,
`The service is currently unavailable`), the final state is uncertain. Check both
the internal track and **Latest releases and bundles** in Console first. If the
expected candidate is already active, record the readback rather than uploading
again. If no new release or bundle is present and the error was transient, retry
the failed distribution job with `gh run rerun <distribution-run-id> --failed`
and review its environment approval again. Reuse the same candidate; do not
rebuild or bump the code to work around an uncertain commit. If a bundle or
draft remains, or another upload reports a used code, reconcile that state before
another attempt. Repeated service failures require investigation, not a retry loop.

Complete the Play Console setup tracked in #2127 separately: store listing,
privacy-policy URL, Data Safety declaration, content rating, and tester access.
An internal upload does not complete that qualification or authorize production
promotion.

### Verified execution: 2026.9.10 / code 13

The first exercised build-to-internal flow used source
`d2417ce5c7c6a3cb1512d3a1037487e931be83e7`:

- [Production build 34458015735, attempt 1](https://github.com/marmot-protocol/whitenoise-android/actions/runs/34458015735)
  succeeded; APK and AAB signatures and the candidate inventory were independently verified.
- Manifest SHA-256: `d7735f7a7f2dd3ccb79b52d3187794dddfb4ac12206e7b4f5f4ef9d274611f5f`.
- [Play distribution 34460426318, attempt 2](https://github.com/marmot-protocol/whitenoise-android/actions/runs/34460426318)
  succeeded after a transient Google commit failure. Console confirmed code 13
  available to internal testers with its mapping file; production stayed inactive.
- No GitHub release or Zapstore publication was performed. This receipt proves
  build and internal distribution, not device behavior or public-store qualification.

These are historical receipts, not candidate IDs to reuse for a future version.
CI artifacts expire after 30 days; retain approved release evidence separately.

## Public Zapstore publication

This step makes the release public. Do not use it for a build rehearsal.

1. Complete candidate qualification and the preliminary release decision.
2. Dispatch **Android Zapstore - PUBLIC Publication** with the reviewed build ID,
   version, and manifest digest. Type `PUBLISH ZAPSTORE <exact-versionName>` in the
   confirmation field, replacing the placeholder with that same version.
3. Independently review and approve the waiting `zapstore-production` environment
   job. Verify the candidate identity and that public exposure is intended.
4. The job retrieves and rechecks the existing bundle, restores its listing,
   and signs a preflight using ZSP offline mode. That preflight can contact the
   remote signer, but does not upload blobs or publish release events.
5. Only after the publisher check passes does the online ZSP command run.
6. Read back the public release events and APK, verify publisher, version,
   hashes and signer, and exercise the shipped updater. A successful command
   alone is not final public-receipt verification.

A failed online command may have partially published. Inspect relay and Blossom
state before retrying. There is no automatic retry, overwrite-release flag,
withdrawal, or legacy-listing removal. `org.parres.whitenoise` is a different
application and cannot migrate installed data by publishing this new package.

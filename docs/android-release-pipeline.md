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
ZSP runs with `--skip-metadata` so external metadata cannot replace reviewed copy.

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

`google-play-internal` contains `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. Give that
account testing-track permissions only. The workflow hardcodes `internal`;
there is no input for production, open testing, or a rollout percentage.

`zapstore-production` contains a scoped `ZAPSTORE_SIGN_WITH` bunker URI and a
stable `ZAPSTORE_BUNKER_CLIENT_KEY` (64 hex characters). The runner restores the
client identity before connecting. The bunker address identifies the remote
signer transport and need not equal the publishing key. A signed preflight
checks the returned event author against `ZAPSTORE_PUBLISHER_PUBKEY` before the
online publication command. Direct nsec signing is not supported by this CI path.

Before the first public release, qualify the pinned real ZSP binary separately
from the publication workflow. Capture its `--version` output and use a
throwaway listing and authorized test signer to verify that `publish --offline`
emits JSON event lines and restores the bunker client identity from
`$XDG_CONFIG_HOME/zsp/bunker-keys/<transport-pubkey>.key`. Offline mode can contact
the signer; do not run the online publication command during this rehearsal.
The automated regression suite uses a fake ZSP and does not establish this
real-binary contract. Record the result in #2128 before public publication.

First-release Android signing-certificate linking, where needed by Zapstore,
is a separate publisher-operated prerequisite. CI uses `--skip-linking` and does
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

`release-manifest.json` binds the source, version, signing fingerprints, build
run/attempt, runtime completeness, and file hashes. This is a verified inventory,
not a cryptographic reproducible-build attestation or proof of device behavior.

Local `just production-release <version>` always builds fresh outputs.
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

Complete the Play Console setup tracked in #2127 separately: store listing,
privacy-policy URL, Data Safety declaration, content rating, and tester access.
An internal upload does not complete that qualification or authorize production
promotion.

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

# Android release pipeline

The release pipeline builds a candidate once and distributes those exact bytes.
Building, Play internal testing, GitHub drafts, and public Zapstore publication
are separate actions. No merge, tag push, or successful build publishes a release.

Product delivery and qualification remain tracked in
[#2125](https://github.com/marmot-protocol/whitenoise-android/issues/2125), with
GitHub/artifact work in #2126, Play in #2127, Zapstore in #2128, qualification in
#2184, and final public receipts in #2387.

Before distributing a release candidate, require a successful **Android
Reproducible Release Verify** tag/manual run for that exact candidate SHA.
An earlier nightly result or a source-only PR's intentionally deferred reproduction
check is insufficient. Retain the run link and source SHA alongside the release
manifest. This is an operator gate in addition to the automated signing and
artifact checks below; it does not claim those workflows enforce it automatically.

## Distribution boundaries

| Manual workflow | Effect | Approval boundary |
| --- | --- | --- |
| **Android Production Build** | Build and verify an ARM64 direct APK and four-ABI Play AAB; retain a candidate bundle for 30 days. No GitHub release or store upload. | `android-release-signing` environment |
| **Android Release - GitHub Draft or Play Internal** | Distribute an existing, reviewed bundle. Default destination is a GitHub **draft**. The other destination releases to Play **internal testing only**. | Explicit dispatch; Play also requires `google-play-internal` approval |
| **Android Zapstore - PUBLIC Publication** | Publish the reviewed APK and listing publicly. This has no private/internal track. | Separate dispatch, version-specific typed confirmation, and `zapstore-production` approval |
| **Android Zapstore - PUBLIC Legacy App Deletion** | One-time NIP-09 deletion of the retired `org.parres.whitenoise` listing and its two releases/assets. It cannot target the current app. | Exact app-specific typed confirmation and `zapstore-production` approval |

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
uses: shared `WHITENOISE_OTLP_AUTH_TOKEN`,
`WHITENOISE_AUDIT_OTLP_AUTH_TOKEN`, and `WHITENOISE_PUSH_RELAY_HINT`, plus
flavor-specific
`WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX`. The compatibility tenant remains
fixed in Gradle. The release workflow fixes metrics and audit URLs to the shared
collector; the old endpoint and production-prefixed audit variables are unused.
The app does not configure a v4 upload destination. Native v4 records may still
remain locally for export, while consented upload uses only the v5 OTLP receiver.

Firebase client configuration, MIP-05 push identity/relay, and opt-in telemetry
are independent systems. Production builds enforce the Firebase/push guards.
Telemetry credentials are ingest-only and compiled into the app; never put a
privileged service credential in a BuildConfig field.

Aptabase product analytics is a separate destination from OTLP metrics and v5 audit delivery.
For a release intended to include it, provision the environment-specific
`PRODUCT_EVENTS_ENDPOINT`, `PRODUCT_APP_KEY`, and `PRODUCT_OPERATOR` fields documented in
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
leave uploads off; disabling sharing must stop new recording and prevent new
upload batches. The pinned native uploader snapshots its configuration for each
batch: a batch already running can finish uploading its files after withdrawal.
The disclosure must state this limit and must not promise deletion of stored logs.

`google-play-internal` contains `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`. Give that
account testing-track permissions only. The workflow hardcodes `internal`;
there is no input for production, open testing, or a rollout percentage.

`zapstore-production` contains a scoped `ZAPSTORE_SIGN_WITH` bunker URI and a
stable `ZAPSTORE_BUNKER_CLIENT_KEY` (64 hex characters). The runner restores the
client identity before connecting. The bunker address identifies the remote
signer transport and need not equal the publishing key. A read-only v2 preflight
checks the returned public key and active C1 authorization against the pinned
publisher before any public write. Direct nsec signing is not supported by this CI path.

ZSP **2.0.0** is pinned with platform-specific SHA-256 checksums in
`config/android-release.properties`. Its `--json check` and `--json publish`
commands return one JSON document: success on stdout, failure on stderr.
V1 JSONL event parsing, `--offline`,
`--skip-certificate-linking` and `RELAY_URLS` no longer apply. CI sets `RELAYS`
and `BLOSSOM_URL` explicitly. The Go protocol helpers use separately pinned,
checksummed dependencies from upstream commit `c50c1ccbf32d7fa6ae00f74990ce2c83f3bac6a1`:
ZSP 0.4.17 and 2.0.0 both use go-nostr 0.52.3 and coder/websocket 1.8.12.
This dependency checkout never supplies the publishing binary.

CI runs `scripts/test-zsp-contract.sh` with a synthetic APK, a disposable Android
key/C1 proof, a disposable Nostr key, a loopback NIP-46 signer, a loopback catalog
and a loopback Blossom server. It exercises the checksummed real v2 binary,
including the production `--json check --channel main` authorization gate,
`publish --apk-hash <reviewed-hash> --channel main`, C1 validation, authenticated
upload, pending JSON, signed event IDs,
release-to-asset references and restoration of the preseeded client identity.
The production readback tool then verifies the accepted event ID, signed metadata
and exact APK bytes served by the loopback CDN.
The signer relay binds to `127.0.0.2` on the Linux runner: v2 special-cases
`127.0.0.1` with a built-in local client key, so this second loopback address
exercises the persistent production identity path. The signer relay rejects
software events. Production credentials and public
services are absent. The `zsp-v2-contract` artifact contains the sanitized
`build/reports/zsp-v2.json` receipt. This CI-covered rehearsal requires Go,
JDK 17+, Android SDK platform 36 and build-tools 36.0.0; do not duplicate it locally.

ZSP uses the platform config directory: on the Linux publication runner the key
is `$XDG_CONFIG_HOME/zsp/bunker-keys/<transport-pubkey>.key`; on macOS it is under
`~/Library/Application Support/zsp/bunker-keys/`. The rehearsal removes only its
own randomly named key file. A synthetic rehearsal does not prove access to the
production signer; use the protected signer test below.

A valid **NIP-C1 certificate ownership proof** must already authorize the pinned
publisher as owner or delegate. The publication preflight verifies the exact APK
hash, app ID, version/code, certificate and signer, and requires the proof to
remain valid for more than 30 days. Missing, expired, unauthorized or renewal-due
proofs stop the job before uploads. The Android key holder creates or renews the
proof locally with the pinned ZSP wizard, using the **direct release APK's signing
keystore**, not the separate Play upload key. Production CI publication never
receives that keystore, its password, a Nostr private key, or Hotbox credentials.

The owner completed the production proof during the 2026.10.27 recovery; its
verified expiry is 2028-10-26T13:52:36Z. Check the live proof before each release;
this historical receipt does not establish future validity. The immutable
2026.10.27 build manifest records producer ZSP 0.4.17. Bundle validation checks
that field against the policy at the bundle's source commit, independently of
the current publisher pin. Never rewrite a reviewed manifest or rebuild its APK
to upgrade the publishing tool.

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
the authorized reviewer, and wait for success. When other changes await review,
the workflow commits the edit without sending it, so the internal release waits in
Play Console's **Publishing overview** under **Changes not yet sent for review**.
Read that whole list first: **Send for review** submits every change in it, not
only this internal release. When nothing else was pending, Play sends this release
for review itself and nothing waits. Either way, confirm the internal release's version
code/name, **Available to internal testers** status, and attached mapping file;
confirm the production track is unchanged. Retain
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
testing as a completed internal release. Play requires `changesNotSentForReview`
while other changes await review and rejects it when nothing else is pending, so
the workflow first commits with it. That upload then stops under **Changes not yet
sent for review**; testers receive it only after the operator sends it from
**Publishing overview**, which submits every listed change, so review the full
list before sending. If that commit fails, the workflow uploads once more in a
fresh edit without the parameter. Play accepts that only when nothing else is
pending, so it submits this internal release alone; a version code already
committed by the first attempt is rejected.
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

### Test the real signer without publishing

Dispatch **Android Zapstore - Signer Test (NO publication)**
(`android-zapstore-signer-test.yml`) on `master`, then approve the existing
`zapstore-production` environment. It uses `ZAPSTORE_SIGN_WITH` and the existing
`ZAPSTORE_BUNKER_CLIENT_KEY`; the first connection can consume the bunker invitation.
Keep the signer available and allow `get_public_key` plus `sign_event` for kinds
3063, 30063, 32267 and 24242.

The test runs the pinned v2 binary's **read-only `check`** on a disposable
`invalid.example.zspfixture` APK against an empty loopback catalog, using the
real bunker and stable CI client identity. It verifies the publisher and that
ZSP retained the client key, then reconnects with the original bunker URI and
signs synthetic invalid.example events for kinds 3063, 30063 and 32267 plus an
already-expired kind 24242 upload authorization. None is uploaded or published.
Keycast permits the consumed invitation for its original active client; retain
the URI secret. Raw signed events, connection URLs and remote errors are
withheld. Logs contain public client/publisher keys and test results only.
This proves v2 connection compatibility and signing permissions; the public
candidate's C1 proof, upload, publication and updater still need their own checks.

```bash
gh workflow run android-zapstore-signer-test.yml --ref master
```

### Publish the reviewed candidate

This step makes the release public. Do not use it for a build rehearsal.

1. Complete candidate qualification and the preliminary release decision.
2. Dispatch **Android Zapstore - PUBLIC Publication** with the reviewed build ID,
   version, and manifest digest. Type `PUBLISH ZAPSTORE <exact-versionName>` in the
   confirmation field, replacing the placeholder with that same version.
3. Independently review and approve the waiting `zapstore-production` environment
   job. Verify the candidate identity and that public exposure is intended.
4. The job retrieves and rechecks the existing bundle and restores its archived
   listing/media into an isolated directory. Its source is restricted to the
   reviewed local APK; external metadata enrichment and image compression are
   disabled. A read-only `zsp --json check` validates the APK, signer, C1 proof
   and monotonic version code. It is bounded to two minutes.
5. A previously published version uses independent readback only. Otherwise,
   the relay must finish both release/asset queries with EOSE and report no
   existing events. CLOSED, disconnects, timeouts and partial releases stop
   publication. Before public writes, the job retains a sanitized
   `zapstore-submission-intent-<version>` artifact.
6. The job invokes `zsp --json publish` **once**, with the exact APK hash/source
   commit and channel `main`, bounded to eight minutes. There are no overwrite
   flags or automatic publication retries. CLI output and signer errors remain
   private; only allowlisted status/error codes and public identity fields are
   retained in the receipt.
7. `pending` means accepted for Zapstore's APK verification, **not public**.
   Even `published` or exit zero must pass independent readback. For up to ten
   minutes, CI reads the signed app/release/asset events from `ZAPSTORE_RELAY`,
   checks the accepted release ID (when returned), publisher, version/code,
   source commit, certificate, APK hash and size, then downloads the APK from
   `ZAPSTORE_BLOSSOM` and verifies size/SHA-256. Both the legacy hash path and
   v2 `<hash>.apk` path are accepted on that exact CDN; redirects are rejected.
   Exercise the shipped updater separately.

Only `status: verified_public` in the sanitized `zapstore-publication-<run>-<attempt>`
receipt means CI verified public delivery. The receipt is uploaded even if the
job fails. `pending`, `submission_unknown` and `unresolved` require reconciliation;
a signer timeout or lost response can occur after acceptance. Keep the receipt
and do **not** resubmit, delete events or overwrite a release to repair the state.

To resume, dispatch the same protected workflow with the same reviewed build,
version, manifest and typed confirmation, setting **`verify_only: true`**.
This performs readback without connecting the signer or issuing `publish`.
Workflow reruns also use verification only, and a prior submission-intent artifact
from this master workflow blocks another submission for that version. Intent
artifacts are retained for 90 days; download and preserve them with release
evidence. Beyond that retention window or after artifact deletion, the automatic
barrier cannot prove a previous submission absent: use verification only and
reconcile the retained receipt/relay/CDN using the recovery procedure below
before any newly authorized attempt.
An intent may exist even if no public write occurred (for example, cancellation
after its upload); that still requires reconciliation rather than a blind retry.

A pending release that does not become public fails CI with its receipt retained.
Inspect Zapstore processing/rejection and the signed event IDs before deciding
on a corrective release. C1 renewal remains a holder action. This workflow does
not enable Zapstore's external forge polling or tag-triggered publication; public
approval remains separate from build/GitHub/Play internal testing.
`org.parres.whitenoise` is a different application and cannot migrate installed
data by publishing this package.

### Recover a failed submission without losing its evidence

The intent barrier deliberately survives a failed job. A fresh dispatch or a
workflow rerun does not authorize a second write. There is one holder-operated
reset procedure; it is never performed automatically by CI:

1. Stop concurrent publication for this version. Download **every** matching
   `zapstore-submission-intent-<version>` artifact and the corresponding
   `zapstore-publication-<run>-<attempt>` receipt. Match the original run and
   attempt recorded in each intent, not a later verification-only rerun. Save
   the artifacts, their GitHub IDs/run URLs and SHA-256 checksums in the release
   evidence outside the immutable candidate directory.
2. Establish the failure stage. An original receipt with `not_submitted` proves
   this script never invoked the public CLI. `submission_started`,
   `submission_unknown`, `pending`, a missing receipt, or an exit/error code
   alone does **not** prove absence of writes. In those cases keep verification
   only until Zapstore confirms the exact release/asset/hash has no accepted or
   pending submission. A public relay EOSE with no event is insufficient: the
   buffer zone can hide accepted events. Rate limiting or signer failure alone
   is also insufficient evidence.
3. A release approver must independently confirm the same reviewed candidate,
   the complete prior-attempt evidence, no matching relay release/asset events,
   and no accepted/pending Zapstore submission. Record explicit authorization
   **`RESET ZAPSTORE <version> AFTER RUN <run>/<attempt>`** with the reconciliation
   evidence. If any of those facts remains unknown, do not reset; retain the
   barrier and use verification only or prepare a newly versioned candidate.
4. Only after that authorization, a maintainer may remove the specific GitHub
   intent artifact(s) identified in step 1, using
   `gh api --method DELETE repos/marmot-protocol/whitenoise-android/actions/artifacts/<artifact-id>`.
   Preserve the downloaded audit copies and record the deletion IDs/time and
   approver. Never delete the workflow runs, receipts, signed events, or release
   evidence to clear the barrier. No reset/deletion is part of this migration.
5. Repair the diagnosed cause and run the protected signer test where relevant.
   Dispatch a **new** public-publication run for the same reviewed build,
   version and manifest, with `verify_only: false`, the normal version-specific
   confirmation, and fresh independent `zapstore-production` approval. The new
   run repeats preflight and records a new intent before its single attempt.

This reset is a separately authorized operation, not a retry inferred from
`retryable`, a timeout, or an absent public event. If Zapstore cannot establish
whether a previous submission exists, leave the original version locked. The
public readback failure logs expose the verifier's public relay/CDN reason
(for example, missing release versus wrong blob size); signer output stays private.

### Diagnose remote-signer reply delivery

The signer's replies travel through the relays in the bunker URI. A reply can
reach those relays yet never reach a new client process; the client then waits
without error. In the 2026.9.30 release, Keycast answered every request, but
the CI client missed its reply to the second connection of a run.

**Android Zapstore - NIP-46 Relay Diagnostic (no secrets)** reproduces that
path from a GitHub runner with throwaway keys and a local stand-in signer. It
runs fresh client processes back to back, logs every relay frame, and reports,
for any missed reply, whether the reply was stored on each relay. It reads no
secrets and needs no environment approval.

```bash
gh workflow run android-zapstore-relay-diagnostic.yml --ref master \
  -f relays='wss://relay.primal.net,wss://nos.lol' -f rounds=12
```

Retain the `nip46-relay-diagnostic` artifact with the investigation.

### Retire the legacy package

`Android Zapstore - PUBLIC Legacy App Deletion` is a one-time, hard-scoped
workflow for removing the obsolete `org.parres.whitenoise` catalog entry. It
requires the exact confirmation `DELETE ZAPSTORE org.parres.whitenoise`, uses the
protected `zapstore-production` signer, and refuses any target other than the
five reviewed legacy app, release, and asset events embedded in
`scripts/delete-legacy-zapstore-app.go`.

The workflow publishes a signed NIP-09 kind-5 deletion request containing both
address coordinates and exact event IDs, then reads the relay back. It verifies
that those legacy records are no longer returned and that the current
`dev.ipf.whitenoise.android` app event is unchanged. The deletion request is
public and cannot be undone. CDN blobs and copies retained by other relays or
clients may remain even after Zapstore removes the catalog records.

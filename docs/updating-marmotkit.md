# Updating MarmotKit

White Noise Android consumes one immutable MarmotKit Android release archive.
Normal builds never regenerate bindings from a local MDK checkout. Maintainers
update the reviewed pin in `app/src/main/marmotkit/MARMOT_VERSION` only after
MDK has published the corresponding release.

The [30 October nightly](marmotkit-nightly-20261030.md) is the current testing-only
pin; replace it with the formal `marmotkit-v0.15.0` release once MDK publishes one.
The [official 0.14.0 adoption](marmotkit-0.14.0.md) records the previous artifact,
API compatibility and retained-data qualification, and is the populated campaign's
upgrade baseline. The
[23 October nightly](marmotkit-nightly-20261023.md) and
[22 October nightly](marmotkit-nightly-20261022.md) remain historical, testing-only
evidence bound to their exact revisions and 30-day artifact retention.

## Markdown timestamps

The Android timestamp renderer consumes `MarkdownInlineFfi.Timestamp` and
`MarkdownTimestampStyleFfi` from [MDK #2214](https://github.com/marmot-protocol/mdk/pull/2214),
reviewed at `2336afda22f6100ecb76916bf187cb9c311d3c8d` and merged as `f8b5552e`.
Every MarmotKit snapshot from `marmotkit-snapshot-7692e26649193bd0bfba5f6a425a80b7c2e8f502`
onward contains these types. Do not regenerate or hand-edit bindings or parse
timestamps in Kotlin.

The host formats signed Unix seconds with the current local calendar and locale;
Android ICU supplies localized relative labels. Only relative projections run a
refresh clock: it wakes at the next label boundary (at most hourly), only while the
screen is started, and catches up on restart. Timezone, time and locale broadcasts
invalidate every label and restart that clock, so a corrected wall clock never
leaves a seconds label waiting out an older, longer wait.
Native clock icons and each token's own text range carry hover/tap disclosure.
A linked label keeps navigation while its clock opens disclosure. Message state
retains only canonical typed nodes. Tap disclosure, hover and an in-progress press
survive relative label refreshes; disclosure and hover close when their token leaves
the content.
Calendar values outside `java.time`'s range
remain visible as their exact canonical token rather than wrapping or crashing.

`MarkdownTimestampText` uses the caller's inline artwork map when one is supplied.
Chat previews combine built-in emoji with attachment icons; reply previews keep
the quoted message's emoji map instead of inheriting the containing message's map.

## Group-change admission, recipient lookups and storage parks

Snapshot `88d1683e` adds typed outcomes that Android must not collapse into generic failures (#3289):

- `GroupCatchingUp` and `GroupCommitUndelivered` refuse group changes advisorily. Route every guarded binding
  call through `runGroupChange`, which asks the user, calls the matching `acknowledge…` for one attempt and
  retries. The group's commit lock is released while the user decides, because sends take it too.
  `GroupChangeAdmissionCoverageTest` lists the guarded methods; extend it when MDK guards another.
- `DirectoryLookupIncomplete` means nothing is known to be missing: offer Retry and keep the selection. A
  `MissingKeyPackage` with reason `OBSOLETE_ONLY` asks the recipient to update; only `NOT_FOUND` is missing setup.
- `STORAGE_BLOCKED` keeps the checkpoint and readmits on free space or Retry. Present it as a storage problem,
  never as a retry timer or an ordinary failure.
- `InvalidEditTarget` restores the original text; retrying such an edit can never succeed.

### Recovery qualification

`MarmotKitRecoveryQualificationTest` checks actual decrypted messages against a
loopback relay that clamps queries to 500 events and either refuses or silently
ignores history comparison. The October 22 nightly includes MDK #2513's fixes for
[#2375](https://github.com/marmot-protocol/mdk/issues/2375) and
[#2381](https://github.com/marmot-protocol/mdk/issues/2381). All seven cases must
execute: no ignored recovery case can satisfy adoption.

The populated campaign builds current master and the candidate with one disposable
signer and isolated `.mdkqualification` package. It fails closed if master no
longer pins the reviewed October 23 source `d274dd22`. Dispatch once on the
candidate branch and reuse a matching active run:

```bash
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref <candidate-branch> -f mdk_qualification=true
```

It seeds two accounts, history, retained attachments, a pending upload and cached
avatars through MDK. Upgrade and cold restart must preserve their original bytes
with acquisition disabled, and normal MainActivity must open usable retained
chats. Separate phases promote the pending upload, restart again and recover
corrupt/lost disposable media. Both recovery modes then require all seven named
cases, including the formerly ignored single-sender and long-offline replays.
Avatar seeding uses an immutable public HTTPS image with a checked SHA-256,
respecting MDK's URL validation. Before the first candidate launch, the runner
blocks public IPv4 and IPv6 egress for only the disposable app's UID, while
retaining loopback access to its relay/blob fixtures. It verifies those rules
before upgrade and after cold reads so avatar refetch cannot hide migration loss.
The Android healthy-receive test covers local deletion and restart; MDK's
exact-source composed tests separately cover hydration quarantine and
send-before-drain ordering. Keep those evidence boundaries explicit.

The package disables background boot/job entry points so no preflight migrates
its root before measurement. The encrypted synthetic root stays private on the
runner; artifacts retain its digest, APK/native/signer provenance, storage states,
UI observations and test outcomes. A failed baseline prevents upgrade claims.
Current master already stores completed attachments in files; the old 0113
SQL-body compaction/fault campaign and its byte-pinned hooks do not apply to this
upgrade pair. Its historical evidence remains in the October 21 record.

A disposable debug-signed pair is not proof for a trusted preview APK. Request a
preview only for a concrete handoff/debugging need, then separately qualify the
exact signed APK under [CI policy](ci-request-policy.md). Never use a physical
installation, clear data, downgrade or rewrite migration records for this campaign.

For the historical October 22-to-23 pair, migration 0116 adds retention metadata and a separate
versioned SQLCipher rekey changes the database key presentation. The shared
baseline fixture also seeds an explicitly removed disposable attachment and
records private salt digests. Candidate-only `MarmotKeyMigrationQualificationTest`
requires Forever defaults, preserved removal and salts, and no unfinished key
migration markers after upgrade and cold restart. Its source stays outside the
baseline fixture overlay because the previous bindings lack the new getter.
Retain passing exact-source MDK interrupted-rekey, failed-rekey, WAL process-death
and external-signer recovery results separately from these Android observations.
The official 0.14.0 pair starts after that schema/key transition; the same
assertions now prove retained-data reopening, not a fresh rekey. No new storage
migration is introduced relative to the October 23 nightly. Physical app-performance
evidence remains separate from hosted qualification; phone testing is paused.

## 1. Select and inspect the release

Prefer a formal `marmotkit-v<version>` release. Record its exact tag, asset URL,
and MDK source SHA. Download the Android ZIP and calculate its SHA-256:

```bash
curl --fail --location --retry 3 \
  --output /tmp/marmotkit-android.zip \
  https://github.com/marmot-protocol/mdk/releases/download/<tag>/<asset>.zip
shasum -a 256 /tmp/marmotkit-android.zip
unzip -p /tmp/marmotkit-android.zip '<archive-root>/manifest.json'
```

Confirm that the manifest's release identifier, tag, `source_sha`, workspace
version, Android API and expected files match the release being reviewed. If the
manifest enumerates features, require an exact match with the lock selection.

When feature enumeration is absent, stop the pin update until equivalent
builder-side provenance is retained: the successful publishing run must resolve
the same immutable source and builder commits as the archive, and its Android
build logs must show the effective feature inputs for every ABI. Inspect the
workflow and build script at that exact builder commit, including any step-level
overrides, and trace those inputs to the native Cargo feature flags. Retain the
run, source links, relevant log excerpts and verified asset digest together.
Missing or contradictory evidence blocks adoption; the previous lock value alone
is insufficient. Record this as builder-verified selection while leaving the
manifest's absent feature field explicitly unverified. Never pin a mutable
branch asset or an archive built locally.

## 2. Update the reviewed lock

Edit `app/src/main/marmotkit/MARMOT_VERSION` as one atomic pin:

- `mdk-sha` and matching `mdk-short-sha`
- `artifact-id`, `artifact-tag`, immutable `artifact-url`, and calculated
  `artifact-sha256`
- `archive-root`, manifest/workspace versions, Android API, and features
- the three API signature counts

Keep the old API counts initially. Prepare the new archive with an explicit
override:

```bash
./gradlew :app:stageMarmotKitApiSignature \
  -Pwhitenoise.marmotkit.artifactFile=/tmp/marmotkit-android.zip
```

If the public surface changed, preparation fails closed and reports the actual
type, UniFFI checksum, and helper-declaration counts. Review why they changed,
update the three counts deliberately, and rerun the command. Do not change a
count merely to make preparation pass.

Review `app/build/reports/marmotkit/marmotkit-api-signature.txt`. It records the
three generated Kotlin hashes, public proxy/record/error types, helper APIs, and
UniFFI API checksums. Compare it with the signature artifact from the previous
Android CI run so additions, removals, and signature changes are explicit.

## 3. Validate the consumer

Run `git diff --check` and the manual-guide preflight locally. All CI-covered
compilation, tests and lint run on hosted GitHub CI, including
`:app:stageMarmotKitApiSignature`, Kotlin compilation and the artifact-preparation
contract tests. The staging command above describes the hosted review process;
do not duplicate it locally. Local archive hashing, manifest/ELF inspection and
static generated-source comparison remain required before changing the pin.

In the pull request, confirm these CI paths complete:

- Android compile, unit, lint, and release builds
- x86_64 packaged-MarmotKit instrumented smoke test
- production/staging release-runtime verification
- arm64 reproducibility verification
- PR preview and staging APK packaging where applicable

The refresh is complete only when the lock, generated API signature, consumer
compilation, and native/runtime checks agree on the same immutable artifact.

## Offline and cache behavior

After one successful preparation, Gradle `--offline` reuses the verified
content-addressed cache. For an air-gapped first preparation, use the explicit
artifact override above or set `WHITENOISE_MARMOTKIT_ARTIFACT_FILE`. Overrides
receive the same checksum, manifest, layout, ELF, API, and extracted-cache
validation as downloaded archives.

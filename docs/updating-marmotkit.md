# Updating MarmotKit

White Noise Android consumes one immutable MarmotKit Android release archive.
Normal builds never regenerate bindings from a local MDK checkout. Maintainers
update the reviewed pin in `app/src/main/marmotkit/MARMOT_VERSION` only after
MDK has published the corresponding release.

The [20 October 2026 nightly evaluation](marmotkit-nightly-20261020.md) records its
artifact verification, API changes, migration qualification and 30-day retention
limits. It is testing-only and does not authorize production adoption.

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

`MarmotKitRecoveryQualificationTest` checks a candidate's offline replay, epoch-gap fallback and lookup retry
on recovered messages, against a loopback relay that clamps queries to 500 events and refuses history
comparison. It is an opt-in fixture of about five minutes per comparison mode; run it on a hosted emulator before adopting a pin
that touches replay or recovery:

The populated upgrade campaign builds current master and the candidate with the
same disposable signer and isolated `.mdkqualification` package, then runs both
recovery modes without changing the two ignored upstream cases:

```bash
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref <candidate-branch> -f mdk_qualification=true
```

The isolated package disables its boot receiver and WorkManager job service so a
background preflight cannot migrate the root ahead of measurement. Instrumentation
omits Application scheduling; UI checks launch the normal manifest Application.
The original encrypted synthetic root stays on the runner until job teardown;
artifacts retain its digest, APK/native/signer provenance, storage states, UI
observations and individual test outcomes without account secrets. A failed
baseline prevents dependent upgrade claims. This fixture does not qualify a
preview-signed APK or exact crash-window fault injection. Its baseline adapter
fails closed unless master still uses the reviewed `88d1683e` artifact.

For diagnosis within an already prepared disposable emulator only:

```bash
adb shell am instrument -w -r \
  -e class dev.ipf.whitenoise.android.state.MarmotKitRecoveryQualificationTest \
  dev.ipf.whitenoise.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Pass `-e comparison ignore` to make the relay drop comparison requests instead of refusing them, and
`-e offlineMillis <ms>` to keep the replay receiver offline longer. Two cases stay ignored until MDK fixes
ship: one sender beyond 100 messages ([MDK #2375](https://github.com/marmot-protocol/mdk/issues/2375)) and
a receiver offline past the two-minute replay lookback ([MDK #2381](https://github.com/marmot-protocol/mdk/issues/2381)).
A physical phone sends the 600 replay messages too slowly to stay inside that lookback, so run the replay case
on an emulator; the other cases also pass on a Pixel 9a.

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
version, Android API, features, and expected files match the release being
reviewed. Never pin a mutable branch asset or an archive built locally.

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

Run only the focused local checks needed for the pin:

```bash
./scripts/test-prepare-marmotkit-artifact.sh
./gradlew :app:stageMarmotKitApiSignature :app:compileDevZapstoreDebugKotlin
git diff --check
```

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

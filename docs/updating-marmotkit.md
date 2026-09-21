# Updating MarmotKit

White Noise Android consumes one immutable MarmotKit Android release archive.
Normal builds never regenerate bindings from a local MDK checkout. Maintainers
update the reviewed pin in `app/src/main/marmotkit/MARMOT_VERSION` only after
MDK has published the corresponding release.

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

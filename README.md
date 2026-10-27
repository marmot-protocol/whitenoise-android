# White Noise Android

Android client for White Noise, backed by the Marmot bindings.

## Project Shape

The Kotlin/Jetpack Compose app is a minimal display and Android platform layer.
MDK owns shared product logic and authoritative state. Follow [the agent guide](AGENTS.md#architecture-minimal-android-display-layer)
and [MDK's host boundary](https://github.com/marmot-protocol/mdk/blob/master/docs/marmot-architecture/overview/app-core-boundary.md#host-app-boundary)
when choosing where to implement a change.

## Common Commands

```bash
just test                  # unit tests
just lint                  # ktlint check (read-only)
just format                # ktlint format (rewrites in place)
just debug                 # build dev debug APKs
just install-debug         # install dev debug on connected device
just run-debug             # install + launch dev debug
just apk-production        # signed production arm64-v8a APK (fast)
just apk-staging           # signed staging arm64-v8a APK (fast)
just release               # signed production + staging APKs with pinned MarmotKit
just release-fast          # backward-compatible alias for just release
just install-production    # install production arm64-v8a APK on connected device
just install-staging       # install staging arm64-v8a APK on connected device
just keystore-gen          # one-time release keystore generation
just keystore-fingerprint         # print SHA-256 of production release keystore
just keystore-fingerprint staging # print SHA-256 of staging release keystore
```

Direct Gradle equivalents (Zapstore debug is the default local dev flavor):

```bash
./gradlew :app:testDevZapstoreDebugUnitTest :app:testDevPlayDebugUnitTest
./gradlew :app:assembleDevZapstoreDebug
./gradlew :app:installDevZapstoreDebug
```

Release-like startup and group-flow measurements, Baseline Profile generation,
profile packaging verification, and Compose compiler report commands are
documented in [docs/performance.md](docs/performance.md). Run them on a dedicated
physical device with an authenticated dev test account. The state-preserving
runner replaces the dev package in place with the same debug key and avoids the
connected-test teardown that otherwise removes local fixture data.

The app also has a `distribution` flavor dimension, orthogonal to the
environment above: **`zapstore`** enables verified direct-APK self-updates (the
Zapstore manifest permissions and the installer implementation in the `zapstore`
source set); **`play`** omits that installer machinery entirely and shows no
in-app update UI at all — the distributing store (e.g. Google Play) owns
updates, and Play policy forbids sending users off-store to update. Every build combines one
environment with one distribution and a build type — e.g.
`assembleDevZapstoreDebug`, `assembleProductionPlayRelease`.

The supported build matrix is intentionally small:

- dev debug: `dev.ipf.whitenoise.android.dev`
- staging release: `dev.ipf.whitenoise.android.staging`
- production release: `dev.ipf.whitenoise.android`

Dev registers `whitenoise-dev://` deep links, staging registers `whitenoise-staging://`, and production registers `whitenoise://`. Gradle disables production/staging debug builds and the dev release build so each bundle ID maps to exactly one intended notification environment.

## Continuous Integration

Contributors and coding agents must follow the [CI request policy](docs/ci-request-policy.md):
batch related edits into one candidate push, reuse matching results, retry diagnosed
transient failures selectively, and request preview APKs only when testing needs them.
Core code validation and final readiness/merge requirements remain mandatory.

Every pull request to `master` and every push to `master` starts
`.github/workflows/android-ci.yml`. The workflow uses read-only credentials and public build inputs. Existing detekt findings remain frozen in
`config/detekt/detekt-baseline.xml`.

With the queue precheck switch unset, ready PRs run the complete Zapstore unit,
Roborazzi, coverage and Android lint checks. Play compilation and flavor-sensitive
unit tests run separately. Both distributions retain Android-test Kotlin
compilation. Tooling runs ktlint, detekt, signing/security contracts and Baseline
Profile verification. Legacy drafts defer the heavier ready lanes. Master,
scheduled and manual core runs execute both distributions. Separate runners
shorten the serial path while repeating setup; compare wall time and summed
runner minutes. Local contributors can use `-PciTestForks=3` when appropriate
for their machine; agents run CI-covered checks on GitHub.

The [CI request policy](docs/ci-request-policy.md#queue-gated-pr-feedback-and-full-integration)
describes the opt-in `ANDROID_CI_MODE=merge-queue` mode. Ordinary PRs then use
separate Zapstore and Play main/unit/Android-test Kotlin/Java compile jobs plus
style, tooling and security checks. Full suites, goldens, Kover, lint and baseline
packaging run at integration. Every merge group runs the full core matrix;
`ci:full`, risky inputs or unknown classification evidence also select it on PRs.
Activation remains gated on native queue enforcement, independent integration
authorization and verified source-owner recovery; this change does not activate it.

The existing `Compile, test, ktlint, detekt, Android lint` aggregate remains the
required context. It checks explicit mode and exact expected job outcomes;
unexpected skips, failures, cancellations and missing classification fail closed.
Drafts use `… (draft fast lane)`; readiness requires the ready-head context. In activated mode,
marking a draft ready starts another precheck unless full preflight is selected.
A direct merge requires full current-base qualification. The queue requires fresh full
integration validation plus independent authorization.

Only ordinary Markdown changes in the complete PR diff can use the docs shortcut;
tooling/manual-guide/metadata validators still run. The complete diff retains
earlier code changes during docs fixups. Master stays fully checked in this
first rollout. Compose reports and unsigned reproduction remain risk-selected supplemental
campaigns, including on merge groups. Separate release workflows retain their
conservative qualification policy;
release qualification remains tied to the exact release candidate.

Coverage artifacts are `kover-coverage-report-Zapstore` and
`kover-coverage-report-Play`. Failure reports and Gradle timing profiles use
`android-ci-reports-<job>` and `android-ci-gradle-profiles-<job>`, where `<job>` is
`compile`, `build-contracts-<phase>`, `compose-compiler`, `static-analysis-Zapstore`,
`static-analysis-Play`, `compile-tests-Zapstore`, `compile-tests-Play`, `Zapstore`, or
`Play`. Separate runners
reduce the serial critical path but repeat some setup/compilation; compare both
wall time and summed job durations when measuring CI performance. Each Gradle job
retains its own task outputs on master pushes and same-repository PRs; fork PRs
restore caches read-only. Assertions execute on every fresh runner. The separate
MarmotKit cache retains one writer: the Zapstore suite when it runs. Prechecks and docs-only runs do not write it. GitHub's ephemeral runner
bounds read-only daemon lifetime;
the pinned Gradle setup action stops writable-job daemons before cache cleanup.

The `compile` job compiles the debug main, unit-test and Android-test sources of
Zapstore, and of Play when a Play lane runs, before the unit-test, lint,
build-contract and Play jobs start; they load those outputs from the build cache
instead of compiling them again in parallel. When the baseline phase runs, it
assembles both release APKs, so R8 minifies the benchmark release build once per
run; drafts and prechecks keep that packaging in the tooling phase.

Android CI, the fuzz PR gate and the invariant gate run on wn-build-server, a
persistent self-hosted runner pool registered to this repository. The
path-filtered campaigns (release runtime, reproducible build, instrumented
tests) and PR screenshots stay on GitHub-hosted runners: together with Android
CI they need more CPU and memory than the 8-core, 64 GB host has, and hosted
runners add capacity in parallel. Fork pull requests fall back to
`ubuntu-latest`, and the runner's job-start hook rejects them anyway. All slots
share one Gradle user home and build cache, so the GitHub cache is disabled
there. Every job there checks out with `clean: false` and keeps the module
`build/` directories and `.gradle` between runs for incremental compilation;
one cleaning checkout would erase a slot's compile history.
`.github/scripts/prepare-persistent-workspace.sh` deletes everything else,
including reports, test results and outputs, so test tasks still execute on
every run.
The runner's job-start hook gives the `compile` and `static-analysis` slots three
times and the `tests` slot twice the CPU weight of other slots: they are the
longest chains of a PR run. Larger ratios starved script jobs with wall-clock
timeouts.

`.github/workflows/invariant-gate.yml` runs on every pull request to `master`,
including description edits, without compiling anything. The checker script runs
from the base revision, and the pull request's registry is fetched through the API
as data. The workflow file itself is protected by review, not by the
check; see the registry page for what is and is not protected. A pull request that closes a bug
must change, name or exempt a gate from the [invariant-gate registry](docs/invariant-gates.md);
pull requests that close no bug pass. `InvariantGateRegistryTest` in the unit suite
keeps the registry in sync with every `*CoverageTest.kt`.

Two security workflows run separately from the main Gradle validation so their
permissions and results stay explicit:

- `.github/workflows/codeql.yml` compiles the credential-free dev Zapstore and
  Play debug variants on separate runners and scans each with CodeQL's extended
  security query suite. The existing `Analyze Java and Kotlin` gate requires both
  scans. Zapstore keeps the existing SARIF category; Play has its own category.
  It runs for `master` pushes, a weekly full scan, and manual dispatch, not on
  pull requests; no ruleset requires it. Only its SARIF upload receives
  `security-events: write`.
- `.github/workflows/dependency-submission.yml` supplies GitHub's dependency
  graph with the resolved Gradle graph after each `master` update so repository
  vulnerability alerts can evaluate direct and transitive Gradle dependencies.
  Only this submission workflow receives `contents: write`.

Both security workflows pin every third-party action to a full commit SHA,
cancel superseded work, and have bounded job timeouts. CodeQL's Gradle setup
reuses dependencies but always compiles under the extractor; the dependency-submission action
manages its own Gradle execution. Dependency submission runs only from trusted
`master` pushes because it is the sole workflow with `contents: write`.

Pushes to `master` also run `.github/workflows/android-instrumented.yml`, a
separate emulator workflow for `:app:connectedDevZapstoreDebugAndroidTest`
(Zapstore debug, exercising direct-distribution config) and the controlled
attachment fixture. Master runs the full suite and both fixture distributions.
Ready (non-draft) pull requests that touch a listed input run the focused
`@PullRequestDeviceSmoke` suite and/or the Zapstore attachment fixture, chosen by
`scripts/select_instrumented_lanes.py` from the whole PR diff; an unreadable
diff runs both. It uploads Android test reports when available and retains them
for seven days.

`.github/workflows/android-release-runtime.yml` (minified APKs on ART) and
`.github/workflows/android-repro-verify.yml` (release lint and unsigned
reproduction) run on `master` pushes, nightly, tags, manual dispatch and in the
merge queue. Pull requests run them only when release, signing, build or
dependency inputs change, and not while draft. Neither is a required check.

Pushes to `master` also run `.github/workflows/android-staging-apk.yml`, which
builds and uploads a signed `arm64-v8a` staging release APK for internal
installation. That workflow is also manually runnable from GitHub Actions. It
uses the checksum-verified MarmotKit artifact pinned by the repository and requires these repository Actions
secrets:

- `ANDROID_GOOGLE_SERVICES_JSON_BASE64`
- `WHITENOISE_STAGING_KEYSTORE_BASE64`
- `WHITENOISE_STAGING_KEYSTORE_PASSWORD`
- `WHITENOISE_STAGING_KEY_ALIAS`
- `WHITENOISE_STAGING_KEY_PASSWORD`
- `WHITENOISE_OTLP_AUTH_TOKEN`
- `WHITENOISE_AUDIT_OTLP_AUTH_TOKEN`
- `WHITENOISE_STAGING_PUSH_SERVER_PUBKEY_HEX`
- `WHITENOISE_PUSH_RELAY_HINT`

Human contributors can run these checks locally before pushing. Coding agents
use hosted CI and the [CI request policy](docs/ci-request-policy.md):

```bash
./gradlew :app:compileDevZapstoreDebugKotlin :app:compileDevPlayDebugKotlin
./gradlew :app:testDevZapstoreDebugUnitTest :app:testDevPlayDebugUnitTest  # also: just test
./gradlew :app:verifyRoborazziDevZapstoreDebug :app:verifyRoborazziDevPlayDebug  # screenshot tests
./gradlew :app:ktlintCheck             # style/format check  (also: just lint)
./gradlew :app:detekt                  # code-smell/complexity check
./gradlew :app:lintDevZapstoreDebug :app:lintDevPlayDebug   # Android lint
```

Use `just format` (`./gradlew :app:ktlintFormat`) to auto-fix ktlint findings
before re-running the check.

Dependency freshness is automated via [Dependabot](.github/dependabot.yml),
which opens grouped pull requests **weekly** for GitHub Actions and the
hand-pinned gradle dependencies in `gradle/libs.versions.toml`. Each ecosystem
caps its own open Dependabot PRs at five (the limit is per ecosystem, not
repo-wide), and GitHub Actions bumps are grouped into a single PR. Those PRs run
through the same `android-ci.yml` validation as any other change, so a bump
cannot merge without a green build. Gradle bumps also change locked selections,
checksums and possibly runtime notices, so their CI stays red until a maintainer
completes the reviewed update in
[dependency security](docs/dependency-security.md#reviewed-resolution-and-byte-inputs),
one update at a time. Compose-BOM- and Firebase-BOM-managed
artifacts (and the intentional `material3` alpha pin) are excluded so Dependabot
does not fight the BOMs.

## Screenshot tests

[Roborazzi](https://github.com/takahirom/roborazzi) guards Compose UI against
visual regressions that compile cleanly and pass ordinary assertions but ship a
broken layout. The tests render real composables on the JVM via Robolectric — no
emulator — and cover onboarding, chat lists, conversations, settings, media,
account switching, and other committed UI states across themes and accessibility
configurations.

Robolectric 4.17 on JDK 17+ needs explicit Java module openings. The shared
`tasks.withType<Test>()` configuration in `app/build.gradle.kts` supplies the
[upstream-required flags](https://robolectric.org/getting-started/#running-with-java-17-and-higher)
to unit-test JVMs, including screenshot and synthetic-corpus replay tasks.
Keep these flags on the test processes rather than in `org.gradle.jvmargs`;
they are not app/runtime configuration. An `IllegalAccessException` mentioning
`jdk.internal.access.SharedSecrets` during sandbox startup indicates missing
test-JVM flags, not a screenshot mismatch; do not re-record baselines for it.

Baseline PNGs live under `app/src/test/snapshots/` and are committed to git. CI
runs both complete dev-debug unit suites with `-Proborazzi.test.verify=true`.
The committed-baseline owner registry in `config/screenshot-baseline-owners.txt`
remains a static coverage contract and provides focused local regeneration filters.
Tests named `*ScreenshotTest` are included automatically; a mixed-name test that
owns committed baselines lists its golden-producing methods there.
`scripts/check_screenshot_baseline_owners.py` fails the tooling job when a capturing
class has no registered owner and fails either full-unit job if any committed PNG
was not compared. A mismatch fails the test; diff/compare images are uploaded in
`android-ci-reports-Zapstore` and `android-ci-reports-Play`. All goldens remain
protected. PR descriptions show up to four inline comparisons and preserve links
to the complete visual diff.

**Re-baseline after an intentional UI change.** When you deliberately change a
covered composable, regenerate the baselines and commit the updated PNGs:

```bash
./gradlew :app:recordRoborazziDevZapstoreDebug   # rewrite baselines under app/src/test/snapshots/
# or :app:recordRoborazziDevPlayDebug
git add app/src/test/snapshots/        # review the image diff, then commit
```

Always eyeball the regenerated PNGs before committing — that review is the point
of the check. If `verifyRoborazziDevZapstoreDebug` or `verifyRoborazziDevPlayDebug` fails on a change you did *not* intend,
that is a caught regression: fix the UI, don't re-record.

## Release Builds

The complete Android release process, store metadata, required GitHub
environments, signing-key separation, and guarded publication flow are defined
in [docs/android-release-pipeline.md](docs/android-release-pipeline.md).

Production release builds use signing values from `local.properties` or matching environment variables:

- `WHITENOISE_PRODUCTION_KEYSTORE_PATH`
- `WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD`
- `WHITENOISE_PRODUCTION_KEY_ALIAS`
- `WHITENOISE_PRODUCTION_KEY_PASSWORD`

Production also accepts global signing values as fallbacks:

- `WHITENOISE_KEYSTORE_PATH`
- `WHITENOISE_KEYSTORE_PASSWORD`
- `WHITENOISE_KEY_ALIAS`
- `WHITENOISE_KEY_PASSWORD`

Google Play release bundles use a separate upload key while Play App Signing
retains the direct-distribution production key as the app-signing key:

- `WHITENOISE_PLAY_UPLOAD_KEYSTORE_PATH`
- `WHITENOISE_PLAY_UPLOAD_KEY_ALIAS`
- `WHITENOISE_PLAY_UPLOAD_KEYSTORE_PASSWORD` (local Gradle builds fall back to the production password)
- `WHITENOISE_PLAY_UPLOAD_KEY_PASSWORD` (local Gradle builds fall back to the production password)

The production build workflow requires both Play password secrets explicitly.
Run `:app:bundleProductionPlayRelease -Pwhitenoise.playBundle=true` separately
from APK tasks. Explicit bundle mode disables the production Zapstore variant,
selects the upload key, and disables APK splits. Resolved release APK packaging tasks
are rejected in bundle mode; abbreviated bundle tasks and `clean` are supported.

Staging release builds use staging-only signing values:

- `WHITENOISE_STAGING_KEYSTORE_PATH`
- `WHITENOISE_STAGING_KEYSTORE_PASSWORD`
- `WHITENOISE_STAGING_KEY_ALIAS`
- `WHITENOISE_STAGING_KEY_PASSWORD`

Release packaging fails if signing is unconfigured. To override for an unsigned,
non-publishable local smoke or reproducibility build, set:

- `WHITENOISE_ALLOW_UNSIGNED_RELEASE=true`

Runtime configuration is also read from `local.properties` or environment variables so endpoints and tokens stay out of Git.

**Shared runtime values:**

- `WHITENOISE_OTLP_ENDPOINT` — optional metrics override; staging and production default to `https://otlp.whitenoise.chat/v1/metrics`.
- `WHITENOISE_OTLP_AUTH_TOKEN` — one shared metrics write token for staging and production.
- `WHITENOISE_AUDIT_OTLP_ENDPOINT` — optional override for the shared v5 audit destination, which defaults to `https://otlp.whitenoise.chat/v1/logs` in staging and production.
- `WHITENOISE_AUDIT_OTLP_AUTH_TOKEN` — shared ingest-only v5 audit token, separate from metrics auth.
- `WHITENOISE_PUSH_RELAY_HINT` — shared by staging and production (production defaults to `wss://relay.eu.whitenoise.chat`).

**Development metrics token:**

- `WHITENOISE_DEV_OTLP_AUTH_TOKEN`
The development flavor continues to accept `OTLP_TOKEN_WHITENOISE_ANDROID_DEV`.
Staging and production use the same metrics write token and the deployment
environment resource attribute identifies the flavor in the metrics data.

No tenant secret is read. MarmotKit requires a nonempty tenant resource attribute,
so staging and production both supply `whitenoise-android`; development supplies
`whitenoise-android-dev`. HTTP authentication
uses the bearer token. The separate deployment environment
attribute remains `production`, `staging`, or `development`, respectively.

**Push identities (MIP-05):**

- `WHITENOISE_STAGING_PUSH_SERVER_PUBKEY_HEX` — staging push-server identity.
- `WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX` — production push-server identity
  (`WHITENOISE_PUSH_SERVER_PUBKEY_HEX` remains a production fallback).

**Dev audit and push:**

Dev does not inherit shared audit credentials or staging/production push
identities. Explicit dev configuration remains available:

- `WHITENOISE_DEV_AUDIT_OTLP_ENDPOINT`
- `WHITENOISE_DEV_AUDIT_OTLP_AUTH_TOKEN`
- `WHITENOISE_DEV_PUSH_SERVER_PUBKEY_HEX`
- `WHITENOISE_DEV_PUSH_RELAY_HINT`

Without these values dev uploads and push registration are unconfigured. Local
audit recording is separate. Dev OTLP needs its own token as well as the shared
endpoint. Preview keeps telemetry, audit, and push configuration empty.

`app/google-services.json` is optional for dev, preview, and explicitly unsigned
reproducibility builds. Signed production packaging requires the file and an
Android client for `dev.ipf.whitenoise.android`; `scripts/release.sh` also
verifies that the final APK contains `google_app_id` and
`gcm_defaultSenderId`. When present for other supported variants, the file
should also include clients for `dev.ipf.whitenoise.android.dev` and
`dev.ipf.whitenoise.android.staging`.

### Building a release

```bash
just apk-production
```

Builds the signed production `arm64-v8a` APK using the immutable MarmotKit
artifact pinned in `app/src/main/marmotkit/MARMOT_VERSION`. Gradle downloads it
once, verifies its checksum, provenance, layout, and native architectures, and
reuses the content-addressed cache afterward. The output filename is
`whitenoise-production-v8a-release-YYYY-MM-DD-<sha>.apk`. The release folder is
printed as the final line for Finder.

To build the direct APK and Play AAB once, verify both signing identities and
all ABI/version invariants, and collect the release manifest, checksums, release
notes, and store assets together, run:

```bash
just production-release <version>
```

The manual **Android Production Build** workflow creates the candidate without
publishing. A separate workflow distributes reviewed artifacts to a GitHub draft
or Play internal testing. Public Zapstore publication requires its own workflow,
version-specific confirmation, and protected-environment approval.

The verified bundle is written to `build/production-release/`. Zapstore and
GitHub must distribute the exact APK from that directory; rebuilding or
re-signing it per destination defeats the cross-store update contract.

```bash
just apk-staging
```

Builds the signed staging `arm64-v8a` APK. The output filename is
`whitenoise-staging-v8a-release-YYYY-MM-DD-<sha>.apk`.

```bash
just release
```

Builds all signed production and staging APKs (per-ABI + universal) with the
same pinned MarmotKit artifact. No local MDK checkout or NDK binding rebuild is
required. After one successful preparation, Gradle `--offline` builds reuse the
verified artifact from the Gradle user-home cache. Set
`WHITENOISE_MARMOTKIT_CACHE_DIR` only when an explicit shared cache location is
needed.
For an air-gapped first build, provide the exact pinned ZIP with
`-Pwhitenoise.marmotkit.artifactFile=/path/to/marmotkit-android-0.9.12.zip`
or `WHITENOISE_MARMOTKIT_ARTIFACT_FILE`; the same checksum and provenance checks
still apply.
Python 3 is required for artifact preparation. Gradle selects `python3` on
Unix-like hosts and `python` on Windows; set `WHITENOISE_PYTHON` to override the
executable name or path.

Maintainers updating the pinned release should follow
[docs/updating-marmotkit.md](docs/updating-marmotkit.md). The lock, generated
API signature, and consumer/native checks must move together.

```bash
just release-fast
```

Backward-compatible alias for `just release`; both use the same cached,
checksum-verified artifact path.

### Reproducible unsigned release APK (#1261)

To verify that two isolated builds of the same commit produce identical **unsigned**
production Zapstore `arm64-v8a` release APK bytes (no signing secrets, no
`google-services.json`), see [docs/reproducible-apk-builds.md](docs/reproducible-apk-builds.md)
and run `./scripts/repro-verify.sh`. Tag/manual CI runs publish matching
artifacts plus the recorded toolchain from
[`.github/workflows/android-repro-verify.yml`](.github/workflows/android-repro-verify.yml).

## Device Testing

Release candidates are tested with the stable, numbered [manual release testing checklist](docs/manual-release-testing.md). Use its permanent test IDs in every failure report so results remain actionable across releases.

For local device checks, prefer:

```bash
just install-debug
```

Avoid `connectedDevZapstoreDebugAndroidTest` on Jeff's Pixel unless he asks for it, because it can uninstall the app and wipe local state.

## Performance Guidance

Keep Compose work cheap. Do not call slow binding, database, or network paths from composition or from the main thread.

Use White Noise streams and SQLite-backed projections as the fast path. If Android needs a shape that is expensive to assemble, add or improve the native projection rather than storing a duplicate copy in the Android app.

Close native subscriptions when screens or services stop using them.

## License

Copyright (c) 2024-2026 White Noise developers.

White Noise Android is licensed under the GNU Affero General Public License version 3.0 only (AGPL-3.0-only). See [LICENSE](LICENSE).

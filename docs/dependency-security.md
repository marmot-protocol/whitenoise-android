# Dependency security floors

`gradle/dependency-security.json` records minimum patched versions for modules
already requested by the build. The settings hook is installed before project
evaluation so project plugin classpaths and named tool/test/runtime configurations
receive the same policy. It does not add a dependency or downgrade a newer
release. Metadata and version conflicts remain build failures.

Each resolved configuration writes selected coordinates, selection reasons and
parent edges (including requested selectors) under `build/reports/dependency-security`. The validator checks the
selected versions independently of the hook's violation field, rejects missing
or unresolved evidence, and does not invoke Gradle or download anything. Existing
Gradle CI jobs upload their own reports; there is no additional build/test matrix.
The report represents resolutions observed by that job, not every configuration
or the R8-retained APK. Native MarmotKit requires its own target/feature inventory.

## Distribution and native inventories

Inventory collection is enabled by `CI=true` on GitHub. Local builds keep the
security-floor policy and graph reports without selecting or fingerprinting
artifacts or inspecting POMs. Request local inventory observations explicitly with
`-PdependencySecurity.inventory=true`; `false` overrides the CI default.
When enabled, the hook fingerprints external module artifacts on each already-selected
configuration, memoizing file hashes within the build and licence declarations per
Maven coordinate, using a lenient, component-filtered [artifact view](https://docs.gradle.org/current/userguide/artifact_views.html).
Project components remain in graph edges but are excluded before artifact
selection, so AGP's task-specific project variants are not selected by this hook.
Missing artifacts and variant-selection failures are recorded as configuration-level
`resolution_failure` observations without inventing a component identity. Non-regular
or unreadable files retain their known coordinate and name. These gaps produce
`artifact_coverage: partial` and `unfingerprinted_artifacts`; graph-only consumers
remain usable. Graph resolution failures and selected security-floor violations
still fail verification.
It adds no configurations or compilation tasks. Cached adjacent
POMs supply declared licence names/URLs and their POM hashes when available.
Only the POM's project-level licence declarations qualify. Unparseable cached
POMs, including rejected DOCTYPEs, leave licences `not_observed` without changing
dependency selection; external XML access remains disabled.
Missing declarations remain `not_observed`; a Maven namespace is a supplier hint,
not verified legal ownership. These records are observations, not dependency locks
or a replacement for the existing licence/notices generator.

After the existing blocking security-floor validator succeeds, a separate
`continue-on-error` step in every core lane and dependency submission writes
`build/reports/dependency-security/inventory/selected.json`. The existing
`dependency-security-*` artifacts retain it alongside the raw observations.
Only `:app`'s `productionPlayReleaseRuntimeClasspath` and
`productionZapstoreReleaseRuntimeClasspath` enter `release_runtime`.
Plugin and named test/analysis configurations enter `tooling_test`; other graphs,
including debug runtime and compile classpaths, stay in `other_configurations`.
A job that never resolved a release runtime graph does not claim to have one.
Reports from another source, failed resolution or conflicting selections for the
same configuration are rejected before inventory output.
Inventory-only validation failures warn through the failed observational step
without failing the lane. Explicit artifact coverage and component usage fields
are required by the first schema; no earlier inventory format is assumed.

MarmotKit preparation stages `native-provenance.json` with the API signature.
It binds the complete Android lock hash, upstream source/artifact/manifest hashes,
ABI/target pairs and verified JNI hashes. Features declared only by the Android
lock remain distinct from upstream manifest attestation. If upstream declares
features, preparation and release export require them to equal the Android pin. Workspace Cargo.lock
identity does **not** establish the Android crate link set or licences; absent
upstream target-resolved evidence remains explicitly unverified.

The existing production build additionally uploads
`dependency-inventory-production-<source>-<run>-<attempt>`. Its
`inventory/release.json` requires both release runtime graphs, matching source and
lock identities, exact APK/AAB and mapping hashes from the release manifest, and
matching packaged MarmotKit bytes for the direct APK's one ABI and Play's four.
Other packaged native libraries are hashed but remain unattributed unless their
supplier is established. R8 mappings are retained evidence, not proof of retained
Maven classes. The verified bundle is uploaded first. Inventory generation and
retention are observational and warn on failure without changing the successful
release-run conclusion required by the bundle retrieval tool. This production
export is not yet qualified on a real signed production bundle; that gap is
explicit until an authorized release runs it. Payload bytes, signatures and
distribution permissions are unchanged.

To compare two downloaded inventories, identify both expected source commits:

```sh
python3 scripts/dependency_inventory.py compare \
  --before before.json --before-source <base-commit> \
  --after after.json --after-source <candidate-commit> --output delta.json
```

The delta compares shared configurations, listing newly/missing observations
separately so an absent graph never becomes a dependency removal. Each component
retains its configuration purpose, selection reasons and incoming edges/requested
selectors as observed usage evidence, without inventing a semantic purpose or
legal supplier. Graph-edge, artifact-byte, native-provenance and packaging changes
are reported, including changes at the same coordinate. Unfingerprinted artifacts
make only that configuration's artifact comparison unavailable; other evidence
remains comparable. Native/packaging layers absent from either input are explicit
coverage gaps, never empty deltas.

The existing tooling lane runs this comparison against the same lane's retained
successful `master` push at the tested merge commit's first parent (`HEAD^1`),
not the potentially stale PR event base. It uses the newest unexpired matching
artifact by creation time, with artifact ID breaking ties, so reruns can retain
earlier attempts. No extra Gradle resolution, build, job or matrix is added.
The retained `inventory/base-delta.json` binds both source SHAs and the baseline
run/artifact.
Missing or expired baseline evidence and GitHub API failures, including non-JSON
metadata responses, yield an `unavailable`
receipt; API failures also warn. Malformed or wrong-source evidence writes a
`rejected` receipt with both source SHAs and the error type, warns and exits
unsuccessfully; it never becomes a usable baseline or an empty delta.
The comparison step is informational and continues on error without weakening
the preceding blocking dependency-security checks. The first adoption has no
historical inventory and therefore cannot claim a successful comparison.

## Reviewed floors

| Module family | Minimum | Advisory |
|---|---|---|
| FreeMarker | 2.3.35 | [GHSA-27j2-h3m2-8237](https://github.com/advisories/GHSA-27j2-h3m2-8237) |
| Bouncy Castle prov/pkix/util | 1.86 | [GHSA-9pwp-9qqc-pr26](https://github.com/advisories/GHSA-9pwp-9qqc-pr26), existing build/test floor |
| Wire runtime/JVM | 6.4.5 | [GHSA-9rm7-3qhh-h2mc](https://github.com/advisories/GHSA-9rm7-3qhh-h2mc) |
| Logback classic/core | 1.5.34 | [GHSA-jhq6-gfmj-v8fx](https://github.com/advisories/GHSA-jhq6-gfmj-v8fx) and preceding affected releases |
| jose4j | 0.9.6 | [GHSA-3677-xxcr-wjqv](https://github.com/advisories/GHSA-3677-xxcr-wjqv) |
| JDOM2 | 2.0.6.1 | [GHSA-2363-cqg2-863c](https://github.com/advisories/GHSA-2363-cqg2-863c) |
| Commons Lang3 | 3.18.0 | [GHSA-j288-q9x7-2f5v](https://github.com/advisories/GHSA-j288-q9x7-2f5v) |
| Apache HttpClient | 4.5.14 | [GHSA-7r82-7xv7-xcpj](https://github.com/advisories/GHSA-7r82-7xv7-xcpj), fixed since 4.5.13 |

Build/test paths are not proof of remote APK exploitability. Do not dismiss an
alert solely because a property or constraint declares a patched version. Check
actual configuration selection and compatibility tasks, then refresh GitHub's
graph. Preserve version-family alignment and avoid blanket exclusions or
`transitive=false` workarounds. Revisit the rules as upstream producers adopt
fixed versions; a floor is not a complete advisory scanner or dependency lock.

Wire also rejects 7.0.0 alpha versions before alpha04, per the upstream advisory;
its stable 6.4.5 floor alone cannot express that branch. Exact affected alpha
requests move to alpha04. Dynamic/range selectors retain their selection intent
and use candidate rejection, followed by independent selected-version checks.
A range containing only affected versions fails rather than silently escaping
the requested range.

The graph-only fixture runs in the existing tooling job using a temporary local
Maven repository and no app compilation. It checks exact lower/newer requests,
dynamic selectors, affected-only range rejection, plugin/project report coverage
and external artifacts alongside ambiguous project artifact variants.
The DOCTYPE fixture resolves its JAR using artifact-only metadata so it exercises
the optional licence reader rather than Gradle's own POM parser.
Source archives retain enforcement without Git; CI separately requires
valid commit identity. Detached configurations, settings-plugin classpaths and
included builds are outside this hook and must keep their own dependency checks.

## Reviewed resolution and byte inputs

`gradle/dependency-inputs.settings.gradle` activates strict dependency locking for
project configurations and project buildscript classpaths. The separate
`settings-gradle.lockfile` contains the settings dependency-management/version-
catalog state. Gradle plugin-DSL resolution in settings (including Foojay) does
not persist its plugin graph through that classpath lock; its exact marker, POM,
module and binary checksums remain covered by artifact verification. Do not
report that settings plugin graph as resolution-locked. `gradle/verification-metadata.xml`
verifies SHA-256 for dependency binaries and their metadata; normal invocations
must keep Gradle verification strict. Version/security floors still apply before
selection. A lock that conflicts with an intentional floor/update must be reviewed
and updated, rather than weakening that floor or excluding transitives.

The reviewed bootstrap evidence in `gradle/dependency-inputs.json` binds the exact
lock and verification files to a hosted source revision, observed configuration
coverage, official HTTPS supplier URLs and independent checksum/payload checks.
The check rejects missing module locks, stale byte evidence, incomplete app/plugin
coverage, broad trust exceptions and missing supplier checks. The bootstrap is an
independent supplier cross-check, not an upstream reproducible-build or signature
attestation. Preserve this distinction during updates.

Gradle cannot apply the same project lock mechanism to detached configurations,
its own distribution, SDK manager packages, Robolectric's runtime-fetched SDK
jars or arbitrary external downloads.
Artifact verification still covers Maven artifacts resolved by detached/plugin
configurations. The wrapper distribution hash, pinned Android command-line tools
and existing MarmotKit source/archive/native hashes retain their separate checks.
Do not describe those external inputs as covered by a Maven lock. Synthetic Maven
fixtures deliberately create their own temporary trust roots and never share or
update the production evidence; source archives consume the checked-in inputs
without needing Git metadata.

For a dependency update, change the declared versions first, then use the existing
hosted tooling lane (`gh workflow run android-ci.yml --ref <update-branch>
-f capture_dependency_inputs=true`) to capture candidate locks and SHA-256 metadata with
`--write-locks --write-verification-metadata sha256`. Include optional preview-debug
variants (`-Pwhitenoise.previewE2eTestBuild=true` and
`-Pwhitenoise.previewProviderMatrixBuild=true`) in that capture. Candidate-generation
flags are an explicit review operation, not normal CI validation. The explicit capture runs even when an earlier normal build fails against stale
locks; those failures remain failures, and capture never supplies a successful
replacement validation result. Keep normal CI
read-only and never accept an automatically generated trust root merely to make
an unknown download pass. Retain the complete resolution report and source SHA.

Run `scripts/audit_dependency_candidates.py` against the candidate XML and selected
runtime artifact list to cross-check every artifact against its official repository.
It checks published SHA-256 or separately downloads and hashes supplier payloads;
a mismatch fails, and publication aliases require matching module metadata. Review
the lock diff, supplier changes and any newly selected transitive/metadata artifacts.
Update the local notice catalogue for changed runtime bytes and retain all original
attributions. Record only the matching reviewed supplier records and exact final
file hashes in the evidence manifest, then validate the normal locked build.

For a missing platform classifier, review that exact supplier artifact and add its
SHA-256 plus provenance. Do not add a group-wide trusted-artifact rule, turn off
metadata verification, accept arbitrary alternate checksums or disable verification.
Any unavoidable exception needs an explicit artifact/metadata identity, owning
upstream limitation, reviewed evidence and removal condition; broad exceptions are
not supported by the committed validator. Use the same review process for initial
bootstrap or recovery of a source archive missing its input files. The hosted
integration fixture proves that changed transitives and substituted bytes fail and
that an inspectable, deliberately approved update succeeds without another build
matrix.


Qualify the generated locks with a normal strict invocation before accepting them.
Gradle's lock constraints can introduce additional paths to an already selected
plugin component that bypass an edge-specific exclusion. This bootstrap observed
that with Roborazzi: its core/core-jvm lock constraints expose the upstream JUnit
4.13.2 → Hamcrest 1.3 runtime dependency despite the plugin edge's JUnit exclusion.
Those exact tooling-only selections are retained in the root classpath lock, with
the normal-resolution graph artifact recorded under `post_lock_selection`. Their
checksums were independently checked with the other test/tooling inputs. A bulk
`--write-locks` capture can omit that path, so preserve/requalify this normal-graph
evidence during updates; never repair it with a global exclusion or unlocked
classpath. Guava's two cross-version Android/JRE publications similarly retain
explicit references to their authenticated Gradle module metadata rather than
loosening supplier-path checks for unrelated artifacts.

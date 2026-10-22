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

The `dev.detekt:dev.detekt.gradle.plugin` marker resolves only from Maven Central.
Its reviewed `2.0.0-alpha.6` POM hashes to
`8490ffa5bad1d7f069a8dbbcd8ef90fa832c2a84aa66701f4025d4a2f95e4214`;
the Plugin Portal publishes a different POM for that same coordinate. The narrow
repository filter prevents cached Portal selection from substituting those
unapproved metadata bytes. It leaves the existing supplier evidence, dependency
locks and strict checksum verification intact. Revisit the filter during a
reviewed Detekt update if the publication paths change.

The reviewed bootstrap evidence in `gradle/dependency-inputs.json` binds the exact
lock and verification files to a hosted source revision, observed configuration
coverage, official HTTPS supplier URLs and independent checksum/payload checks.
Each coverage row is `<project> <project|plugin> <configuration>`. Each supplier
entry maps `<coordinate>/<artifact name>` to the URL that confirmed the SHA-256
recorded in `verification-metadata.xml`: a published `.sha256` file, or the
payload itself downloaded and hashed.
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

Dependency updates, including Dependabot PRs, intentionally remain blocked until a
maintainer reviews their new selections and supplier evidence. Dependabot does
not approve a checksum or repin evidence automatically. Its version-change PR is
the starting point; finish the following procedure on that same branch/PR before
merge. This adds a required maintainer review step to Gradle updates.

1. Change declared versions, commit the candidate branch, then request the existing
   hosted tooling lane with `gh workflow run android-ci.yml --ref <update-branch>
   -f capture_dependency_inputs=true`. The explicit capture uses
   `--refresh-dependencies --write-locks --write-verification-metadata sha256`,
   including optional preview-debug variants. It runs even after an earlier normal
   build fails against stale inputs, and never turns that failure into a pass.
   After the candidates are retained, the job restores the committed locks and
   verification metadata and deletes newly created lockfiles. The later strict
   dependency-submission qualification therefore reads the committed trust root,
   never the captured downloads. Every tooling run checks this before qualifying.
   Download its `dependency-inputs-bootstrap` artifact into `<capture>`.
2. Cross-check the captured runtime evidence and checksum candidates:

   ```sh
   python3 scripts/audit_dependency_candidates.py \
     --metadata <capture>/candidate-inputs/gradle/verification-metadata.xml \
     --runtime <capture>/artifact-evidence.json --output <review>
   python3 scripts/audit_dependency_metadata.py \
     --metadata <capture>/candidate-inputs/gradle/verification-metadata.xml --review <review>
   ```

   The runtime input is the capture's `{schema, artifacts}` object. The audit
   checks published SHA-256 or independently downloaded official payloads; it
   retains runtime and publication-mapping bytes. The second command follows
   POM/module counterparts, parent properties and imported BOMs, retaining exact
   supplemental metadata. Unresolved references fail for explicit review. This
   handles metadata missed by a warm Gradle cache without hand-writing records.
3. Assemble a staged candidate, without editing the active checkout:

   ```sh
   python3 scripts/assemble_dependency_inputs.py --capture <capture> --review <review> \
     --supplemental <review>/metadata-closure.json --output <staged> \
     --carry-forward gradle/dependency-inputs.json \
     --evidence-url https://github.com/marmot-protocol/whitenoise-android/actions/runs/<run>/artifacts/<artifact>
   ```

   This generates the lock/checksum manifest, exact coverage, retained compressed
   capture/build inputs and authenticated publication paths. It requires every
   candidate byte to have independent supplier evidence and validates the staged
   bundle. `--carry-forward` keeps the reviewed `post_lock_selection` and
   `supplemental_tools` records and their retained evidence; remove an entry
   only after the new graph no longer needs it. Inspect the complete diff before
   copying `<staged>`'s files into the branch; assembly is not approval. Additional exact platform/tool records can be passed
   as another `--supplemental` file after independent review.
4. Review changed transitive selections, supplier URLs and runtime notices. Update
   the local catalogue for changed runtime bytes while preserving the pinned
   original notice baseline. Run normal hosted strict CI on the final candidate.
   If normal lock constraints expose another plugin path, retain that evidence
   and reassemble using `--inputs <normal-qualified-lock-and-xml-directory>`.
   Never repair an unknown download with broad trust, a global exclusion or an
   unlocked classpath. Keep any failed candidate's evidence for diagnosis.

Land Gradle dependency updates one at a time. Each assembly rewrites
`reviewed_from` and the retained `gradle/dependency-evidence/` files, so two update
branches always conflict; rebase the next one onto the merged update and capture
it again. Grouping a week's Dependabot bumps into one branch keeps that to one
review. Pushing evidence commits to a Dependabot branch stops Dependabot from
rebasing it, and `@dependabot recreate` or `@dependabot rebase` discards those
commits, so finish the update by hand once the evidence is pushed. Gradle only
adds checksum entries; records for versions the build no longer reads stay in
the XML until a reviewed cleanup removes them, and the locks keep them unselected.

`gradle/dependency-evidence/` retains the original captured reports, original
candidate inputs and build-source snapshots as deterministic gzip with a pinned
source-bound receipt. `config/licenses/evidence/` retains the original plugin's
actual notice index/text and its source snapshot. Historical CI links are context;
validation no longer depends on those artifacts or temporary merge commits being
reachable. The original source identity is preserved rather than relabelled as a
later revision. The supplier audit remains a cross-check, not a reproducible-build
or upstream signature attestation.

Dependency submission pins graph plugin 1.5.0 and independently checked POM/module/
shadow JAR hashes. Both its master action and the PR tooling qualification override
the action's default verification-off flag with strict verification. The PR step
runs the same pinned action's generate-and-upload mode, without permission to
submit to GitHub's dependency API; only the master workflow publishes the graph.
The plugin's detached init-script classpath is checksum-verified, not project-locked.

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
the normal-resolution graph report recorded under `post_lock_selection` and
retained in `gradle/dependency-evidence/post-lock-selection/`. The validator
requires each exception's coordinates in a committed lock, its metadata in the
checksum records and any retained copy to match its pinned SHA-256. Their
checksums were independently checked with the other test/tooling inputs. A bulk
`--write-locks` capture can omit that path, so preserve/requalify this normal-graph
evidence during updates; never repair it with a global exclusion or unlocked
classpath. Guava's two cross-version Android/JRE publications similarly retain
explicit references to their authenticated Gradle module metadata rather than
loosening supplier-path checks for unrelated artifacts. A supplier URL counts as
direct evidence only when its path is exactly the registry root and the
coordinate's group, module and version directories, ending in either the recorded
artifact name or, for a Gradle module metadata display name such as `animation.aar`
or `datastore-release.aar`, the coordinate's own canonical
`animation-android-1.13.0-beta01.aar` file. Any other path, including an inserted
directory, a classifier or another file, needs an authenticated mapping. Only the
two Guava publications and one clikt sources jar need that today; their exact
module JSON files are retained under `gradle/publication-metadata/`, and offline
validation checks their SHA-256 and rederives each artifact's unique resolved URL.
Keeping a valid metadata anchor while changing the supplier URL therefore fails
qualification.

AGP creates `androidApis` lazily when resource tasks need local SDK jars. Its
empty Maven lock state is committed for all three Android modules, and candidate
capture predeclares that known `maybeCreate` configuration so regeneration keeps
it. Strict locking remains active: adding a Maven module to that SDK-only path
fails instead of becoming an unreviewed exception. The hosted fixture resolves a
late local-file configuration and exercises that negative case.

External artifacts transformed into directories are reported under
`unfingerprinted_artifacts` with an explicit kind/reason and partial fingerprint
coverage. The collector never opens such a directory as an archive or invents a
binary checksum. Selected components/security floors remain checked; comparisons
report that configuration's artifact comparison as unavailable while its other
evidence stays comparable. A hosted real directory-transform fixture exercises
this reporting seam.

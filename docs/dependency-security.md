# Dependency security floors

`gradle/dependency-security.json` records minimum patched versions for modules
already requested by the build. The settings hook is installed before project
evaluation so project plugin classpaths and named tool/test/runtime configurations
receive the same policy. It does not add a dependency or downgrade a newer
release. Metadata and version conflicts remain build failures.

Each resolved configuration writes selected coordinates, selection reasons and
parent edges under `build/reports/dependency-security`. The validator checks the
selected versions independently of the hook's violation field, rejects missing
or unresolved evidence, and does not invoke Gradle or download anything. Existing
Gradle CI jobs upload their own reports; there is no additional build/test matrix.
The report represents resolutions observed by that job, not every configuration
or the R8-retained APK. Native MarmotKit requires its own target/feature inventory.

## Distribution and native inventories

The same hook fingerprints external module artifacts on each already-selected
configuration; it adds no configurations or compilation tasks. Cached adjacent
POMs supply declared licence names/URLs and their POM hashes when available.
Only the POM's project-level licence declarations qualify. Unparseable cached
POMs, including rejected DOCTYPEs, leave licences `not_observed` without changing
dependency selection; external XML access remains disabled.
Missing declarations remain `not_observed`; a Maven namespace is a supplier hint,
not verified legal ownership. These records are observations, not dependency locks
or a replacement for the existing licence/notices generator.

After the existing security-floor validator succeeds, CI writes
`build/reports/dependency-security/inventory/selected.json`. The existing
`dependency-security-*` artifacts retain it alongside the raw observations.
Only `:app`'s `productionPlayReleaseRuntimeClasspath` and
`productionZapstoreReleaseRuntimeClasspath` enter `release_runtime`.
Plugin and named test/analysis configurations enter `tooling_test`; other graphs,
including debug runtime and compile classpaths, stay in `other_configurations`.
A job that never resolved a release runtime graph does not claim to have one.
Reports from another source, failed resolution or conflicting selections for the
same configuration are rejected before inventory output.

MarmotKit preparation stages `native-provenance.json` with the API signature.
It binds the complete Android lock hash, upstream source/artifact/manifest hashes,
ABI/target pairs and verified JNI hashes. Features declared only by the Android
lock remain distinct from upstream manifest attestation. Workspace Cargo.lock
identity does **not** establish the Android crate link set or licences; absent
upstream target-resolved evidence remains explicitly unverified.

The existing production build additionally uploads
`dependency-inventory-production-<source>-<run>-<attempt>`. Its
`inventory/release.json` requires both release runtime graphs, matching source and
lock identities, exact APK/AAB and mapping hashes from the release manifest, and
matching packaged MarmotKit bytes for the direct APK's one ABI and Play's four.
Other packaged native libraries are hashed but remain unattributed unless their
supplier is established. R8 mappings are retained evidence, not proof of retained
Maven classes. No release payload, signature or distribution permission changes.

To compare two downloaded inventories, identify both expected source commits:

```sh
python3 scripts/dependency_inventory.py compare \
  --before before.json --before-source <base-commit> \
  --after after.json --after-source <candidate-commit> --output delta.json
```

The delta records selected additions/removals with purpose and supplier namespace,
plus artifact-byte changes, including changes at the same coordinate. It rejects
wrong source identities or different observed configuration coverage: an absent
graph is not evidence of a removal. CI does not manufacture a baseline or claim
an empty delta when no comparable earlier artifact is available.

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
dynamic selectors, affected-only range rejection and plugin/project report
coverage. Source archives retain enforcement withoutGit; CI separately requires
valid commit identity. Detached configurations, settings-plugin classpaths and
included builds are outside this hook and must keep their own dependency checks.

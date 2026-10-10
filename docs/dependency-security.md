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

# Retained presentation dependencies

The local icon source set contains the 108 glyphs currently used by production and
tests plus AndroidX's vector helper from 1.7.8. The two source archives and their
SHA-256 values are recorded in `material-icons-provenance.json`. Only package
and import prefixes changed; paths, dimensions, mirroring, caches and upstream
copyright headers are retained. Apache 2.0 and copyright attribution are also
packaged as the local Material icon notice.

Run `python3 scripts/check_local_material_icons.py` after importing or updating
an icon. When adding a glyph, obtain the matching pinned upstream source,
verify the source archive digest, copy that one definition with the same package
rewrite, and update the reviewed source inventory/hash. Existing goldens verify
rendering; changes in geometry need intentional capture review. The generated
data has narrow formatter/static-analysis/coverage exclusions; app-owned readers,
scanner owners and lifecycle code remain checked.

ML Kit and Google's runtime licence activities are removed. ZXing already
encoded QR data and now decodes bounded CameraX luminance crops. The local build-time notice generator consumes exact runtime artifact fingerprints
and reviewed attribution in `config/licenses/`. It preserves the original 197
production notice rows (196 distinct name/text pairs) and the separate local
Material icon notice.
The baseline and catalogue pin their source revision and original capture.
The original index/text and build inputs are retained in `config/licenses/evidence/`
so attribution remains auditable after hosted artifact expiry. Every variant
generation checks the pinned baseline and icon copyright before writing resources.
Additional inherited POM attributions, debug dependencies and the full alternate
Dagger licence are retained too. J2ObjC uses the complete original notice, which
includes the copyright and embedded native/source notices present in the shorter
SDK variants. No new claim of complete MDK Rust-crate attribution is made: a
reviewed native crate/feature-to-notice attestation remains an upstream prerequisite
for expanding that coverage. ExifInterface 1.4.2 remains explicitly test-only for privacy fixtures.
Security Crypto remains for legacy skipped-version imports. This is a reduction
of shipped stacks, not a claim that every tool/test dependency is unnecessary.

The reader references generated raw resource IDs directly. Reflective name lookup
lost those resources in an optimized preview after the runtime licence SDK was
removed. Existing release runtime verification now checks the APK's actual index,
text and local attribution before installation, without another build or matrix.


The generator runs without network access and rejects unreviewed artifact bytes,
missing attribution, changed/truncated text and invalid UTF-8. It emits the same
`third_party_license_metadata` / `third_party_licenses` raw resources with byte
ranges consumed by the existing reader. New dependency bytes require a reviewed
catalogue update; do not reuse an old dependency's notice merely because its name
matches. Record supplier/POM/embedded-notice provenance, pin the text digest and
preserve every baseline attribution. An intentional baseline change requires an
explicit attribution review rather than regenerating the baseline from a candidate.

A failed generation lists every unreviewed artifact with its observed SHA-256 and
whether it is new or changed. For each one, add or update the exact
`artifacts[]` entry in `catalog.json` (`coordinate`, file `name`, `sha256` and
the `notice_ids` it ships). A new licence text goes in `texts/<sha256>.txt` with
a `notices[]` entry (`id`, `name`, `text`, `sha256`, `source`). The baseline must
equal the retained original plugin output in `config/licenses/evidence/`, so a
catalogue edit cannot drop an original notice.

`verify_notice_resources.py` follows the optimized APK's resource table to the
actual ZIP entries, validates every UTF-8 range, checks the baseline's full notice
set and compares the local icon copyright bytes. Existing Zapstore ART lanes and
the Play preview staging step use this check without another build matrix.

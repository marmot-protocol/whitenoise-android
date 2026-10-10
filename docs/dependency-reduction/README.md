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
encoded QR data and now decodes bounded CameraX luminance crops. The existing
Google build-time notice generator is retained until a replacement can preserve
all notices. ExifInterface 1.4.2 remains explicitly test-only for privacy fixtures.
Security Crypto remains for legacy skipped-version imports. This is a reduction
of shipped stacks, not a claim that every tool/test dependency is unnecessary.

The reader references generated raw resource IDs directly. Reflective name lookup
lost those resources in an optimized preview after the runtime licence SDK was
removed. Existing release runtime verification now checks the APK's actual index,
text and local attribution before installation, without another build or matrix.

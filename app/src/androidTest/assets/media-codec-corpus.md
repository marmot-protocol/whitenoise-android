# Containment fixtures

`aosp-heifwriter-input.heic.b64` is the unmodified 1920 by 1080 HEIF fixture from
Android CTS, tag `android-16.0.0_r1`,
[heifwriter_input.heic](https://android.googlesource.com/platform/cts/+/refs/tags/android-16.0.0_r1/tests/tests/graphics/res/raw/heifwriter_input.heic).
Android CTS is distributed under Apache License 2.0. The decoded file is 15,769
bytes, SHA-256 `62dfb44160403ca8355a874cecc91cdbce57e98dd597fa36a2af55ef54c017ac`.
It is a platform compatibility fixture, not a claim of real camera coverage.

Other fixture pixels are generated locally: red/blue GIF and platform-encoded
WebP frames, JPEG/PNG from 2 by 2 red pixels, and a striped 108-megapixel PNG
encoded row by row. Malformed inputs mutate those harmless encodings or contain
tiny invalid SVG/EXIF documents. No personal messages, accounts or private
photographs are included. Passing these tests does not qualify 200-megapixel
camera images, every HEIF implementation, or common messenger animations.

The corpus also includes unmodified Android CTS `animated.gif` (34,978 bytes,
SHA-256 `eec5e745032b9775d67f040d9ab95ae3dc296100ce0c5d6bf95667bf2d27d2a6`)
and `animated_webp.webp` (24,754 bytes,
SHA-256 `00a108b42a6877076e96c3cde6d75e03c6eb80639489e05ddcf509867669f666`),
from the same tag's [graphics drawables](https://android.googlesource.com/platform/cts/+/refs/tags/android-16.0.0_r1/tests/tests/graphics/res/drawable/).
Their unchanged bytes exercise admission and genuinely changing stream pixels.
They are Apache-2.0 platform fixtures; they do not replace common keyboard,
GIPHY, messenger or personal-camera qualification.

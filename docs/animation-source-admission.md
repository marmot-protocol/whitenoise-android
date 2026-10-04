# Animated image source admission

Any authenticated group member can send image bytes. That includes valid GIF and
WebP files built to make Android's native animated decoder do a lot of work.
Android now checks a GIF or WebP's container from its bytes before
`ImageDecoder` sees it. This is presentation work for the Android client; it does
not validate protocol data or replace MDK media rules.

**Status: provisional.** The limits below have not yet been qualified against an
ordinary-media corpus or on a physical API 36 device. Keep the pull request in
draft and the `MED-026` box unchecked until the
[qualification steps](#qualification-pending) pass.

## Where admission runs

| Entry point | Behaviour |
|---|---|
| `admitAnimationSource` (`media/AnimationSourceAdmission.kt`, `AnimationSourceGif.kt`, `AnimationSourceWebp.kt`) | Pure Kotlin with no Android dependencies. It walks container metadata without copying the payload, and it never decodes pixels. |
| `MediaPipeline.decodeAnimatedDrawable` | The only route to the native animated decoder. It calls `ImageDecoder.createSource` only for an `Admitted` source. Chat attachments, GIPHY cards and animated profile avatars all use this route. |
| `decodeMessageAttachmentImage` → `decodeAdmittedAttachmentImage` | Chooses the decoder for chat bubbles, the viewer and GIPHY cards. |

Admission looks only at content. The sender's advertised MIME type cannot turn
admission on or skip it.

| Result | Native animated decoder | Sampled still (`decodeSampledBitmap`) | User sees |
|---|---|---|---|
| `Refused` (a recognized GIF/WebP that is malformed or over a limit) | not called | not called | The existing could-not-load placeholder |
| `Admitted` (a GIF or animated WebP within every limit) | called | only if the native decode fails, which shows the first frame of the admitted canvas | Animation, or a still |
| `NotAnimation` (not a GIF/WebP signature, or a still WebP that passed the full walk) | not called | called | The existing still path |

## Limits

| Limit | Value | Applies to |
|---|---|---|
| Canvas edge | 4096 px | GIF logical screen, VP8X canvas |
| Canvas area | 4 Mi pixels (4,194,304) | same |
| Frames | 1000 | GIF image descriptors, ANMF chunks |
| Aggregate work | 32 Mi pixels (33,554,432), provisional | canvas area × frame count |

Each frame counts as the full canvas area, even when its rectangle is smaller,
because the decoder still composites it onto the whole canvas. The work figure
caps compositing effort. It does not predict peak memory. All arithmetic uses
`Long`, and walks stop as soon as a limit is crossed.

Ordinary shapes that fit: 480 × 480 × 24, 500 × 500 × 96, 320 × 320 × 300 and
240 × 240 × 580. A 2048 × 2048 canvas allows 8 frames. Larger, longer
animations, for example 1920 × 1080 × 20, are refused. Qualification must
confirm that ordinary media does not hit the work limit.

## GIF rules

- The 13-byte logical-screen header must be complete, with a positive width and
  height. The global and local colour tables must fit in the file.
- Every image descriptor must be complete, with a positive rectangle entirely
  inside the logical screen. The LZW minimum code size must be from 2 to 8, and
  the sub-block chain must end with a terminator. LZW data is skipped, not
  decoded.
- Extensions start with a fixed first block size: graphic control 4,
  application 11, plain text 12. Plain-text, comment and unknown extensions are
  walked as bounded metadata.
- The trailer must be the last byte, and at least one frame must come before it.
  Any other block introducer is malformed.

## WebP rules

- The RIFF size must equal the file length minus 8. Every chunk, including
  chunks nested inside ANMF, must fit with its padding byte when its size is
  odd. Padding bytes must be zero, including nested frame chunks.
- A **simple** file (first chunk `VP8 ` or `VP8L`) is `NotAnimation` only when no
  later chunk is `VP8X`, `ANIM`, `ANMF`, `ALPH`, `VP8 ` or `VP8L`. Other metadata
  chunks are allowed.
- An **extended** file must have a 10-byte VP8X payload. Reserved VP8X bits are
  ignored, as the specification requires.
  - Without the animation flag, the file must contain exactly one still image (see the grammar below) whose bitstream
    dimensions equal the canvas. Any `ANIM` or `ANMF` chunk is malformed, so a still header cannot hide animation.
  - With the animation flag, the file needs exactly one 6-byte `ANIM` before any `ANMF`, at least one `ANMF`, and no
    top-level `ALPH`, `VP8 ` or `VP8L`. Each ANMF stores its offsets halved, so the rectangle is
    `(2·X, 2·Y, W+1, H+1)` and must lie inside the canvas. Duration, blend/dispose and reserved bits have no
    resource cost and are ignored.
- **Still-image grammar** (for ANMF frame data and extended stills): an optional
  `ALPH` immediately followed by `VP8 `, or a `VP8L` on its own. The bitstream
  header must match the declared rectangle. Inside ANMF, the image chunk must
  come first; unknown chunks may follow it. Nested chunks must fill the ANMF
  payload exactly.
- **ALPH:** with compression 0 (raw), the payload must be exactly
  `1 + width × height`. With compression 1 (lossless), the dimensions are
  implicit, so only a non-empty body is required. Values 2 and 3 are malformed.
  Filtering, pre-processing and reserved bits are left to the decoder.
- **VP8 header** checks follow libwebp's `VP8GetInfo`: it must be a shown key
  frame with profile ≤ 3, a first-partition size smaller than the chunk, the
  start code `9d 01 2a`, and non-zero 14-bit dimensions. **VP8L header** checks
  require signature `0x2f` and version 0.

## Compatibility changes to qualify

These containers are valid enough for some decoders but are now refused, or
handled differently:

- A GIF with bytes after the trailer, a frame that extends past the logical
  screen, or a 0 × 0 logical screen.
- A WebP whose RIFF size does not match the file exactly (including trailing
  bytes), whose VP8X or ANIM chunk is larger than its specified size, or whose
  raw ALPH chunk has extra bytes.
- Content that is neither GIF nor WebP never reaches the native animated decoder.
  ISO-BMFF image sequences (animated AVIF/HEIF) and a `image/gif` label on PNG
  bytes now render as stills through the sampled path.
- Animated profile avatars also go through the chokepoint. A GIF that passes the
  stricter avatar limits (1024 px edge, 2 MiB) but exceeds the 32 Mi-pixel work
  budget, for example 1024 × 1024 × 33, falls back to the static avatar.

## Qualification pending

1. Run an ordinary corpus through `admitAnimationSource` and record every refusal
   with its reason, canvas and frame count. The corpus should include recent
   GIPHY trending and sticker downloads, iOS White Noise GIF sends, and animated
   WebP from common Android keyboards and messengers. Adjust the work limit based
   on that evidence before release; do not ship refusals of normal GIFs.
2. On a physical API 36 device, run `AnimationSourceAdmissionDeviceTest` and
   `MED-026`.
3. Run the native animated WebP case: it uses the platform's lossless encoder
   over our own 2 x 2 solid-colour pixels and preserves its compressed frame
   chunks inside a two-frame animation. It must animate under a deliberately
   incorrect still MIME, and an oversized-canvas variant must be refused.
   These tiny fixtures do not replace common-keyboard/messenger qualification.

## Controlled fixtures for MED-026

Build these from a small, ordinary GIF or WebP by editing only container fields.
Do not create pixel-heavy files, and never decode a refused fixture outside the
app under test:

- **Wide GIF:** set the logical-screen width (bytes 6–7, little-endian) to 4097.
- **Long GIF:** repeat one frame (graphic-control extension plus image block)
  1001 times before the trailer.
- **Truncated GIF:** remove the final byte (the `0x3b` trailer).
- **Mislabelled animation:** send an ordinary animated WebP with the MIME type
  `image/png`. It should still animate. Repeat with a wide-canvas variant: it
  should be refused.
- **Hidden animation:** take a VP8X still WebP, append a 6-byte `ANIM` chunk, and
  update the RIFF size.

## Tests

- `AnimationSourceAdmissionTest`: GIF and WebP boundary matrix, every-prefix
  truncation, overflow-size headers, partial frames, masquerades, and a seeded
  mutation sweep.
- `AnimationSourceGateTest`: decoder spies proving that a refusal reaches
  neither decoder, and that only an admitted native failure falls back to a
  still.
- `ImageContainerBytesFuzzTest` (existing campaign): runs the exact production
  admission sources and checks limit invariants.
- `AnimationSourceAdmissionDeviceTest`: uses the real platform codecs with an
  ordinary tiny GIF, platform-encoded WebP frames and harmless refusal variants.
  It is a PR device-smoke class; changes to the guards or caller trigger the
  existing instrumented workflow. CI's API 34 emulator is not physical API 36 evidence.

Out of scope: static JPEG/PNG decode budgets, other still-decoder callers,
receive-side allocation limits (tracked separately), and any MDK or protocol
rule.

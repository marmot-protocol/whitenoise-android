# Outgoing video metadata policy

Issue [#3376](https://github.com/marmot-protocol/whitenoise-android/issues/3376).
Encryption protects an attachment in transit and at rest, but recipients decrypt
the exact bytes the sender chose. A camera video can carry its recording place,
time, device and author. Before a local video is accepted for upload, the app
removes the container metadata named below. Photos already follow the image
policy described in [Media containment](media-containment.md).

## How it works

[`VideoMetadataScrubber`](../app/src/main/java/dev/ipf/whitenoise/android/media/VideoMetadataScrubber.kt)
rewrites the container in place. It never re-encodes or remuxes: Android's
muxer writes its own version, device and time fields back into the file, and a
remux can lose edit lists or HDR signalling. Removed fields become zeros, and
whole metadata boxes or elements become padding of exactly the same length.
The output therefore has the input's size, every offset and index stays valid,
the audio and video sample bytes are never touched, and scrubbing a scrubbed
file changes nothing.

Content decides the container, never the reported MIME type. Every size and
offset is checked against the file, nesting depth and element count are
bounded, and sample tables are streamed through fixed buffers.

## Policy

| Container | Removed | Kept |
| --- | --- | --- |
| ISO BMFF and QuickTime (MP4, MOV, M4V, 3GP, 3G2) | `udta` (including `©xyz` and 3GPP `loci` locations, authors, titles, comments and the encoder tag), `meta` (including QuickTime keys such as make, model, software, ISO 6709 location and creation date), `uuid` (for example XMP) and `prft` boxes in the movie, track, media and fragment structure, and every top-level box outside the movie layout, all become zeroed `free` boxes. Existing `free`, `skip` and `wide` payloads are zeroed. `mvhd`, `tkhd` and `mdhd` creation and modification times are zeroed. Handler names, and the vendor and compressor name of video sample entries, are zeroed. Every sample of a timed-metadata track is zeroed inside its `mdat`: any handler other than video, audio, auxiliary video, picture, subtitle and text, for example location, timecode, camera motion, hint or vendor telemetry tracks. | `ftyp`, the track structure, timing, edit lists, the display matrix (rotation), dimensions, sample descriptions and codec configuration, language, track references, `sidx`, `mfra`, the metadata tracks themselves (with zeroed samples) and every audio and video sample. |
| Matroska and WebM | `Tags`, `Attachments`, `Chapters` and unknown Segment-level elements become `Void` elements of the same length. Segment and neighbouring file names, the recording date (`DateUTC`), the title, and track and codec names are voided. The mandatory muxing and writing application strings are kept as empty strings. `CRC-32` elements of the rewritten `Info` and `Tracks` are voided, and existing `Void` bodies are zeroed. | The EBML header, `SeekHead`, `Cues`, every `Cluster`, track numbers, codec IDs and private data, language, timestamp scale, duration and the random Segment and track identifiers. |

Not covered:

- Data inside the coded audio and video bitstreams, such as H.264 or HEVC SEI
  user data and encoder banners written into the first audio frame.
- The sample descriptions of timed-metadata tracks, such as a timecode reel name.
- Subtitle, caption and text tracks, which are kept as user-visible content.
  Some cameras, such as drones, write location telemetry into subtitle tracks.
- The file name, which follows the existing display-name sanitizing, and what
  the choice of codec or container reveals.
- A video whose provider reports a non-video MIME type. It is sent through the
  Files path as a generic document, and generic documents stay byte-for-byte.
- Forwarding an existing message, which reuses its uploaded reference. Earlier
  uploads are not sanitized retroactively.

## Refusals

A container the scrubber does not handle (for example AVI, MPEG transport
streams or WMV), a malformed structure, a fragmented MP4 with timed-metadata
tracks, a compressed QuickTime movie box and a track whose samples live in
another file are refused. The user sees a notice that private details could not
be removed and the video is not attached. The app never falls back to the
original bytes.

## Entry points

Every local video selection reaches the scrubber before preparation accepts it:

- Photo Picker and incoming shares, in memory:
  `MediaPipeline.readVideoForUpload` scrubs its private copy before the poster
  probe and the upload read.
- Large videos staged to a private file:
  `ConversationAttachmentReader.readFileBackedVideo` scrubs the snapshot inside
  `FileBackedPickBudget.stage`, before the probe and before any disk is reserved.
- Files picker: a document whose MIME type is `video/*` is scrubbed in memory in
  `readRawDocument` or as a staged snapshot in `readFileBackedDocument`.
- Prepared and native drafts, including drafts an older app version saved:
  `prepareStagedAttachments` re-checks every prepared video with
  `removePreparedVideoMetadata` on a copy, so the native draft's own array is
  never rewritten and clean drafts are reused without a copy.

Incoming shares are imported into app-private files and then read through the
same readers. The in-app camera captures photos only.

## Verification

`scripts/generate_video_metadata_fixtures.sh` regenerates the tagged MP4, MOV,
timecode MP4, Matroska and live WebM fixtures with ffmpeg. The JVM tests
`VideoMetadataScrubberFixtureTest`, `IsoBmffMetadataScrubberTest` and
`MatroskaMetadataScrubberTest` check removal, sample identity, size, idempotence
and malformed input. `VideoUploadMetadataCoverageTest` drives every entry point
above. `VideoMetadataScrubberDeviceTest` plays each scrubbed fixture through
Android's own retriever and extractor and compares every sample with the
original.

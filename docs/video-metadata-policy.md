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

The scrubber detects the container from the content, never from the reported
MIME type. Every size and offset is checked against the file, nesting depth and
element count are bounded, and sample tables are streamed through fixed
buffers. `VideoMetadataScrubber.classify` runs the same walk over a read-only
view of the bytes, so deciding whether a file is a video and cleaning it never
disagree.

## Policy

| Container | Removed | Kept |
| --- | --- | --- |
| ISO BMFF and QuickTime (MP4, MOV, M4V, 3GP, 3G2) | `udta` (including `©xyz` and 3GPP `loci` locations, authors, titles, comments and the encoder tag), `meta` (including QuickTime keys such as make, model, software, ISO 6709 location and creation date), `uuid` (for example XMP) and `prft` boxes in the movie, track, media and fragment structure, and every top-level box outside the movie layout, all become zeroed `free` boxes. Existing `free`, `skip` and `wide` payloads are zeroed. `mvhd`, `tkhd` and `mdhd` creation and modification times are zeroed. Everything after each handler's type is zeroed: the QuickTime component manufacturer, flags and mask, and the handler name. The vendor of every audio and video sample entry, and the compressor name of video sample entries, are zeroed. Every sample of a timed-metadata track is zeroed inside its `mdat`: any handler other than video, audio, auxiliary video, picture, subtitle and text, for example location, timecode, camera motion, hint or vendor telemetry tracks. | `ftyp`, the track structure, timing, edit lists, the display matrix (rotation), dimensions, sample descriptions other than their vendor and compressor name, codec configuration, language, track references, `sidx`, `mfra`, the metadata tracks themselves (with zeroed samples) and every audio and video sample. |
| Matroska and WebM | `Tags`, `Attachments`, `Chapters` and unknown Segment-level elements become `Void` elements of the same length. Segment and neighbouring file names, the recording date (`DateUTC`), the title, and track and codec names are voided. The mandatory muxing and writing application strings are kept as empty strings. `CRC-32` elements of the rewritten `Info` and `Tracks` are voided, and existing `Void` bodies are zeroed. | The EBML header, `SeekHead`, `Cues`, every `Cluster`, track numbers, codec IDs and private data, language, timestamp scale, duration and the random Segment and track identifiers. |

Not covered:

- Data inside the coded audio and video bitstreams, such as H.264 or HEVC SEI
  user data and encoder banners written into the first audio frame.
- The sample descriptions of timed-metadata tracks, such as a timecode reel name.
- Subtitle, caption and text tracks, which are kept as user-visible content.
  Some cameras, such as drones, write location telemetry into subtitle tracks.
- The file name, which follows the existing display-name sanitizing, and what
  the choice of codec or container reveals.
- Through Files, a video in a format the classifier does not recognise (for
  example a raw H.264 or HEVC elementary stream), and a well-formed
  video-handler ISO BMFF file whose brand is not on the video allowlist, when
  either is reported with a non-video type. They are sent as ordinary
  documents, byte-for-byte.
- Forwarding an existing message, which re-uploads its original plaintext
  unchanged. Earlier uploads are not sanitized retroactively.

## How each path decides

The visual picker (Photo Picker, gallery and shared media) and its drafts decide
by reported type: anything reported as `video/*` is scrubbed, and refused if the
scrubber cannot clean it.

The Files picker and its drafts decide by content first, because Android's MIME
table maps some ordinary files to `video/*` (a TypeScript `.ts` file is reported
as `video/mp2t`) and some providers report videos as `application/octet-stream`:

| Content | Reported `video/*` | Reported anything else |
| --- | --- | --- |
| A proven video: Matroska or WebM with a video track, or ISO BMFF with a video brand (or no `ftyp`, as old QuickTime) and a video track | Scrubbed, refused if that fails | Scrubbed, refused if that fails |
| An unreadable container: ISO BMFF or Matroska whose first box or element parsed, refused as malformed (for example by a non-box trailer) or before its track list could be read (a compressed QuickTime movie, or a Matroska DocType other than `matroska` or `webm`), with no video track proven. A staged file that cannot be opened or read while it is classified is refused the same way, unless the walk had already read the complete track list and found no video track | Refused | Refused |
| Any other ISO BMFF or Matroska: a complete track list with no video track (for example `.m4a` or audio-only WebM, including one followed by an ID3 tag or other trailing bytes), a still-image or unrecognised brand read without a malformed structure (a Canon CR3 raw photo, HEIF or AVIF), or no movie at all (a HEIF still) | Scrubbed, refused if that fails | Sent byte-for-byte |
| A container the scrubber cannot clean whose content proves video: AVI (RIFF form `AVI `, while RIFF `WAVE` is ordinary audio), DV, MPEG-TS or M2TS, MPEG-PS and MXF by their signature, FLV whose header flags video or whose first tags hold a video tag, Ogg whose beginning-of-stream pages name Theora, Daala, Dirac or OGM video, ASF with a video Stream Properties Object (in the header or its extension), and RealMedia with a video stream | Refused | Refused |
| A container the scrubber cannot clean whose streams cannot be determined within the bounded header read: a truncated or broken header, an unknown codec or stream type, or no declared stream (for example a bare Ogg Skeleton or an FLV with no stream flag) | Refused | Refused |
| A container the scrubber cannot clean whose headers prove audio only: Ogg whose only beginning-of-stream pages are Vorbis, Opus, FLAC, Speex or CELT (a Skeleton may accompany them), ASF (WMA) whose streams are all audio (script commands may accompany them), FLV flagged audio-only whose first tags hold no video tag, and RealMedia with audio streams only | Refused | Sent byte-for-byte |
| Anything else, including text whose first box does not parse even though bytes 4 to 7 spell a box type (`let skipped = 0;` in a `.ts` file) | Sent byte-for-byte | Sent byte-for-byte |

The uncleanable containers are probed with fixed, small reads: Ogg looks at up
to 64 beginning-of-stream pages, FLV at up to 64 tag headers, ASF at the
headers of up to 256 header objects and a Header Extension Object of at most
64 KiB, and RealMedia at up to 64 chunks before its Data chunk. Nothing is
allocated in proportion to a size the file declares. Only what precedes the
media data is read, so a chained Ogg file whose later link adds a video stream
is judged by its first link.

Content overrides a non-video reported type only for a proven video, for an
uncleanable container that proves or might hide video, and for an unreadable
ISO BMFF or Matroska container. ISO BMFF
counts as one only when its `ftyp` major brand is a known video brand (`isom`,
`iso2` to `iso9`, `mp41`, `mp42`, `mp71`, `avc1`, `M4V `, `M4VH`, `M4VP`,
`qt  `, `MSNV`, `dash`, `f4v `, `mmp4`, `dby1`, the `3gp`, `3g2` and `3gs`
families, the Sony `ND…` and `XAVC` brands) and no major or compatible brand
names a still image (`crx `, `heic`, `heix`, `heim`, `heis`, `hevc`, `hevx`,
`hevm`, `hevs`, `mif1`, `mif2`, `msf1`, `miaf`, `avif`, `avis`, `avio`, `jpeg`,
`jpgs`, `avci`, `avcs`, `vvic`, `vvis`), or when it has no `ftyp` at all. Canon
CR3 raw photos and HEIF or AVIF image sequences keep their image tracks under
the video handler, so without this rule they would be rewritten. A movie is
walked before a non-box trailer can refuse the file, so a trailer never hides a
video track: a tagged MP4 with a trailer, reported as a generic file, is a
proven video whose scrub fails, and is refused rather than sent with its tags.
A failed walk proves nothing about video, so a recognised container that the
walk refuses is never sent unchanged, whatever its reported type. The one
exception is a refusal after the walk has read the complete track list (the
ISO BMFF movie box, or the Matroska Segment's `Tracks`) and found no video
track: such a file is audio-only, for example an `.m4a` with an ID3v1 tag
appended or an audio-only WebM followed by stray bytes. A refusal before or
while the track list is read stays unreadable, and a file with a video track
and a trailer stays a proven video, so both are still refused.

A still-image brand is a document whether or not its walk succeeds. Its
metadata is outside this video policy: a well-formed CR3 or HEIF image sent
through Files already goes out unchanged, so refusing only the malformed ones
would add no privacy.

Imported audio keeps its original data, so audio-only containers, including
ones with trailing bytes after a complete track list, are sent byte-for-byte
unless they are reported as video. Reported as video, they are scrubbed, and an
audio-only file with a trailer is then refused because the scrub fails.

## Refusals

A visual pick reported as video, or a Files pick refused by the table above, is
not attached when the scrubber cannot clean it: an unsupported container (for
example AVI, MPEG transport streams or WMV), a malformed structure, a fragmented
MP4 with timed-metadata tracks, a compressed QuickTime movie box or a track
whose samples live in another file. The user sees a notice that private details
could not be removed. The app never falls back to the original bytes.

Known limit: bytes after the last top-level box are accepted only when they are
all zero, so a recording with a non-box trailer after the movie is refused rather
than cleaned. Recordings from real phones (Pixel, Samsung including slow
motion, and iPhone) have not been measured against this rule yet, and nothing
here claims that every phone recording is accepted.

## Entry points

Every local video selection reaches the scrubber before preparation accepts it:

- Photo Picker and incoming shares, in memory:
  `MediaPipeline.readVideoForUpload` scrubs its private copy before the poster
  probe and the upload read.
- Large videos staged to a private file:
  `ConversationAttachmentReader.readFileBackedVideo` scrubs the snapshot inside
  `FileBackedPickBudget.stage`, before the probe and before any disk is reserved.
- Files picker: `readRawDocument` (in memory) and `readFileBackedDocument`
  (staged snapshot) apply the content routing above.
- Prepared and native drafts, including drafts an older app version saved:
  `prepareStagedAttachments` re-checks visual drafts with
  `removePreparedVideoMetadata` and Files drafts with
  `removePreparedDocumentVideoMetadata`. Both work on a copy, so the native
  draft's own array is never rewritten and clean drafts are reused without a
  copy. A video draft that carries a staged file instead of bytes is refused.

Incoming shares are imported into app-private files and then read through the
same readers. The in-app camera captures photos only.

## Verification

`scripts/generate_video_metadata_fixtures.sh` regenerates the tagged MP4, MOV,
timecode MP4, Matroska and live WebM fixtures with ffmpeg. The JVM tests
`VideoMetadataScrubberFixtureTest`, `IsoBmffMetadataScrubberTest` and
`MatroskaMetadataScrubberTest` check removal, sample identity, size, idempotence
and malformed input, and `VideoContentClassifierTest` checks every routing row,
brand rule and container signature. The CR3, HEIF and AVIF cases follow the
published box layouts. No real CR3 file has been tested. `VideoUploadMetadataCoverageTest` sends tagged picks
and a legacy draft through `ConversationMediaSender.sendStagedAttachments` and
reads the exact bytes and files handed to MDK, and drives every reader path
above. `VideoMetadataScrubberDeviceTest` scrubs a video written by Android's own
`MediaMuxer` with a location, and plays each scrubbed fixture through Android's
retriever and extractor, comparing every sample with the original.

The `controller-video-metadata` attachment-fixture mode links preparation to
the recipient on a hosted emulator. `VideoMetadataFixtureDeviceTest` hands
tagged videos to `sendStagedAttachments` through its content-URI preparation
entry points (visual and Files picks in memory and above the in-memory limit,
a `ShareFileImporter` import and prepared draft injection), sends them through
MDK to the loopback fixture and downloads each as the receiver. The received
bytes must equal the scrubbed source and keep every sample, and an AVI or a
trailing-byte MP4 must be refused with no upload. It drives no system picker or
share sheet UI, its drafts are injected rather than saved, force-stopped and
restored, and it records no phone, so MED-043 remains the receipt for those.

#!/usr/bin/env bash
# Regenerates the tagged video fixtures used by the video metadata scrubber tests (#3376).
#
# Every clip is a synthetic one-second 48x32 test pattern with a sine tone, tagged with obviously fake private
# values (WNPrivate* strings, made-up coordinates and a fixed 2024 date). No real recording or person is involved.
# H.264 SEI NAL units are dropped so the x264 banner does not sit in the bitstream; the AAC encoder still writes
# its own banner into the first audio frame, which is bitstream data the scrubber deliberately leaves alone.
#
# Usage: scripts/generate_video_metadata_fixtures.sh
# Needs ffmpeg with libx264, libvpx and libopus (ffmpeg 9.0.2 produced the committed files). Encoder versions change
# the bytes, so the tests assert the private fields and structure rather than fixed digests. The script writes the
# same Base64 text to the JVM test resources and the instrumentation assets, and prints ffprobe tags for review.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd -P)"
jvm_dir="$root/app/src/test/resources/video-metadata"
device_dir="$root/app/src/androidTest/assets/video-metadata"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$jvm_dir" "$device_dir"

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
pattern=(-f lavfi -i "testsrc=size=48x32:rate=10:duration=1")
tone=(-f lavfi -i "sine=frequency=440:sample_rate=8000:duration=1")
h264_aac=(-c:v libx264 -preset veryfast -profile:v baseline -pix_fmt yuv420p -bsf:v filter_units=remove_types=6
  -c:a aac -b:a 16k -ac 1)
common_tags=(-metadata title=WNPrivateTitle -metadata comment=WNPrivateComment -metadata artist=WNPrivateAuthor
  -metadata location=+48.8584+002.2945/ -metadata creation_time=2024-05-06T07:08:09Z)

# MP4 with iTunes-style tags, a 3GPP location box, recording times and custom handler names; movie box first.
ff "${pattern[@]}" "${tone[@]}" "${h264_aac[@]}" "${common_tags[@]}" \
  -metadata:s:v handler_name=WNPrivateVideoHandler -metadata:s:a handler_name=WNPrivateAudioHandler \
  -movflags +faststart "$work/mp4-tagged.mp4"

# QuickTime with Apple-style keys (make, model, software, ISO 6709 location, creation date), a QuickTime location
# string and a 90 degree display matrix; movie box after the media data.
ff "${pattern[@]}" "${tone[@]}" "${h264_aac[@]}" "$work/mov-base.mov"
ff -display_rotation:v:0 90 -i "$work/mov-base.mov" -c copy -movflags use_metadata_tags \
  -metadata com.apple.quicktime.make=WNPrivateMake \
  -metadata com.apple.quicktime.model=WNPrivateModel \
  -metadata com.apple.quicktime.software=WNPrivateSoftware \
  -metadata com.apple.quicktime.location.ISO6709=+12.3456-065.4321+010.000/ \
  -metadata com.apple.quicktime.creationdate=2024-05-06T07:08:09+0000 \
  -metadata creation_time=2024-05-06T07:08:09Z -metadata location=+12.3456-065.4321/ \
  "$work/mov-quicktime-keys.mov"

# MP4 with a timecode track: its single sample carries a time of day, so the scrubber must zero it.
ff "${pattern[@]}" "${tone[@]}" "${h264_aac[@]}" -timecode 10:11:12:13 \
  -metadata creation_time=2024-05-06T07:08:09Z -metadata comment=WNPrivateComment "$work/mp4-timecode.mp4"

# Matroska with Tags, an attachment, a title, a recording date, track names and CRC-32 elements.
printf 'WNPrivateAttachmentNote\n' > "$work/note.txt"
ff "${pattern[@]}" "${tone[@]}" "${h264_aac[@]}" -attach "$work/note.txt" -metadata:s:t mimetype=text/plain \
  "${common_tags[@]}" -metadata:s:v title=WNPrivateTrackName -metadata:s:a title=WNPrivateAudioName \
  "$work/mkv-tagged.mkv"

# WebM written to a pipe, so the Segment keeps an unknown size, with Tags, a title, a date and a track name.
ff "${pattern[@]}" -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=1" \
  -c:v libvpx -b:v 50k -c:a libopus -b:a 12k -ac 1 "${common_tags[@]}" -metadata:s:v title=WNPrivateTrackName \
  -f webm pipe:1 > "$work/webm-live-tagged.webm"

for clip in mp4-tagged.mp4 mov-quicktime-keys.mov mp4-timecode.mp4 mkv-tagged.mkv webm-live-tagged.webm; do
  base64 < "$work/$clip" | fold -w 76 > "$jvm_dir/$clip.b64"
  cp "$jvm_dir/$clip.b64" "$device_dir/$clip.b64"
  echo "== $clip ($(wc -c < "$work/$clip" | tr -d ' ') bytes)"
  ffprobe -hide_banner -loglevel error -show_entries format_tags:stream_tags:stream_side_data=rotation \
    -of compact "$work/$clip"
done

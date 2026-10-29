# Android test media

`silent-black-10s.mp4.b64` is the Base64 encoding of AndroidX Media's
[`silent-black-10s.mp4`](https://github.com/androidx/media/blob/9c18cd338f36180f7435e830023950b0caa683da/libraries/test_data/src/test/assets/media/mp4/silent-black-10s.mp4)
test fixture at blob `788895dc81441b9202a5b1828f1276b71eb92271`. It is used by the
Media3 configuration-rotation instrumentation test and by the pending-video poster
instrumentation test, and decodes to SHA-256
`83fbcd994ece32535285a0ea6505c681cb96c471736308316eabda90cced9f51`.

AndroidX Media is licensed under the Apache License 2.0. The exact license from the pinned
fixture revision is included as [`LICENSE-ANDROIDX-MEDIA.txt`](LICENSE-ANDROIDX-MEDIA.txt).
That upstream revision does not contain a `NOTICE` or `NOTICE.*` file.

## Edge-marked viewer clips

`edge-portrait-180x320.mp4.b64`, `edge-landscape-320x180.mp4.b64` and
`edge-rotated-320x180-r90.mp4.b64` are original White Noise test clips used by
`MediaViewerVideoControlsAndroidTest`. Each is six seconds of a static 15 fps
Constrained Baseline H.264 frame, `yuv420p`, without audio. The frame is a neutral grey
(`#808080`) body with 16-pixel bands: yellow top, cyan bottom, red left and magenta right.
The rotated clip stores a 320×180 frame with a −90° display matrix, so it must play back
as a 180×320 portrait frame.

This ffmpeg recipe produces equivalent clips. Encoder versions and settings change the
bytes, so `EdgeClip` pins each committed clip's decoded SHA-256 instead:

```sh
clip() {
  ffmpeg -f lavfi -i "color=c=0x808080:s=${1}:r=15:d=6" \
    -vf "drawbox=x=0:y=0:w=iw:h=16:c=yellow:t=fill,drawbox=x=0:y=ih-16:w=iw:h=16:c=cyan:t=fill,drawbox=x=0:y=16:w=16:h=ih-32:c=red:t=fill,drawbox=x=iw-16:y=16:w=16:h=ih-32:c=magenta:t=fill" \
    -c:v libx264 -profile:v baseline -pix_fmt yuv420p -an -movflags +faststart "${2}"
}
clip 180x320 edge-portrait-180x320.mp4
clip 320x180 edge-landscape-320x180.mp4
ffmpeg -display_rotation:v:0 -90 -i edge-landscape-320x180.mp4 -c copy -movflags +faststart \
  edge-rotated-320x180-r90.mp4
for clip in edge-*.mp4; do base64 -i "$clip" -o "$clip.b64"; done
```

## Tagged video metadata clips

`video-metadata/*.b64` are original White Noise test clips used by
`VideoMetadataScrubberDeviceTest` and, as identical copies under
`app/src/test/resources/video-metadata/`, by the JVM scrubber tests (#3376). Each is
one second of a 48×32 ffmpeg test pattern with a sine tone, tagged with fake private
values: `WNPrivate*` strings, made-up coordinates and a fixed 2024 date. They cover an
MP4 with iTunes tags and a 3GPP location, a QuickTime file with Apple keys and a 90°
display matrix, an MP4 with a timecode track, a Matroska file with Tags and an
attachment, and a WebM with an unknown-size Segment.
`scripts/generate_video_metadata_fixtures.sh` regenerates all of them; encoder versions
change the bytes, so the tests assert fields and structure rather than digests.

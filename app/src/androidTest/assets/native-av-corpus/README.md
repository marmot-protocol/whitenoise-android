# Synthetic audio/video samples

The four one-second videos contain solid-red 64×48 frames at five frames per
second, encoded as H.264, VP9, AV1 and HEVC. The four one-second audio files
contain a generated 600 Hz sine wave at 48 kHz, encoded as AAC, Opus, MP3 and
PCM16. These samples contain generated pixels and samples only.

`MediaAvNativeCorpusDeviceTest` checks bounded poster colors, duration,
waveforms and fresh restricted-worker receipts. These fixtures cover basic
format handling; camera files, HDR fidelity and performance need separate tests.

`sparse-h264-aac-with-default-ac3.mp4` has four solid-red H.264 frames five
seconds apart and two generated 600 Hz audio tracks. AC-3 is marked default
and AAC is supported. There are 705 AAC packets before the fourth video packet.
The device test reads the first video frame, then confirms decoded AAC is available.

`short-audio-h264.mp4` pairs ten seconds of 60 fps H.264 with 200 ms of
generated AAC audio. The device test makes optional audio yield when demux
reaches a video packet, then checks that all video frames and both track endings
are still available.

`sparse-color-h264.mp4` contains 64×48 red, green and blue frames at 0, 5
and 10 seconds, displayed for five seconds each. Paused poster probes at 7,
10 and 12 seconds must return green, blue and blue respectively.

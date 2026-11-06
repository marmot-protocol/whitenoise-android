# Synthetic audio/video samples

The four one-second videos contain solid-red 64×48 frames at five frames per
second, encoded as H.264, VP9, AV1 and HEVC. The four one-second audio files
contain a generated 600 Hz sine wave at 48 kHz, encoded as AAC, Opus, MP3 and
PCM16. These samples contain generated pixels and samples only.

`MediaAvNativeCorpusDeviceTest` checks bounded poster colors, duration,
waveforms and fresh restricted-worker receipts. These fixtures cover basic
format handling; camera files, HDR fidelity and performance need separate tests.

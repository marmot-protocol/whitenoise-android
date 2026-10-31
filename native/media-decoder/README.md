# Selected-descriptor media decoder

The JNI library reads one read-only regular-file descriptor range. Its custom
AVIO reports the selected length, clamps reads and seeks to that range, and
refuses every secondary `io_open`. FFmpeg is built with network, protocols,
devices, hardware acceleration, encoders and muxers disabled. Media3 and Android
platform media clients are outside this library.

`lock.json` pins FFmpeg 9.0.2 and the dav1d 1.5.4 software AV1 decoder, their
source archives, and the Meson/Ninja wheels used by the build. FFmpeg's GPL,
nonfree and version-3-only components remain disabled. FFmpeg's LGPL-2.1-or-later
text and dav1d's BSD license are retained in `licenses/`. Meson and Ninja are
build tools with Apache-2.0 licenses. The corresponding upstream sources and
the build recipe remain available through the pinned URLs and this directory;
application distributors must retain the source and relinking materials required
by the included licenses.

## Process restrictions

Library loading installs an additional process-wide seccomp filter before any
selected media is opened. `PR_SET_NO_NEW_PRIVS` must succeed, then
`SECCOMP_SET_MODE_FILTER` with `SECCOMP_FILTER_FLAG_TSYNC` must return exactly
zero. A negative error or a positive unsynchronized thread ID refuses the load;
there is no thread-local fallback. Existing Android filters remain in force.
The added filter rejects fork, vfork, clone3 and exec syscalls. Legacy clone
requires `CLONE_THREAD`, `CLONE_VM` and `CLONE_SIGHAND` together, allowing native
threads while preventing a separate child process. It checks the compiled audit
architecture and rejects x32 syscall numbers on x86_64.

`generate_seccomp_filter.py` produces the committed `seccomp_filter.h`. The
builder requires byte identity and includes both files in cache and provenance
digests. Host tests evaluate those emitted BPF records for every supported ABI;
NDK compilation checks syscall numbers, audit identifiers and argument offsets
against its headers. Host tests do not establish kernel enforcement. API-level
and ABI-specific execution must qualify existing Java threads, new Java threads
and native threads against the loaded filter.

The bounded zero-argument diagnostic reports caller and new native-thread
seccomp/no-new-privilege state and a harmless null-argument exec denial probe. A
separate clone3 null-argument probe belongs in its own disposable test process,
because an existing Android filter may terminate that process with SIGSYS. These
diagnostics
cannot establish process death or replace the parent's watchdog, kernel PID
liveness check, capability revocation and cleanup barriers. Binder endpoint death
alone is not proof that the worker process has exited.

## Build

`python3 scripts/prepare_media_decoder.py --cache-key` prints the content key
without downloading or compiling. Repeat `--abi NAME` to select a subset;
omitting it selects arm64-v8a, armeabi-v7a, x86 and x86_64. The key includes those
ABIs, the dependency lock, JNI source, export map, seccomp header and generator,
and build script.

The source build uses a Linux x86_64 host, Python 3.12 or newer, and Android NDK
29.0.14206865 targeting API 30. Contributors and GitHub Actions use the same
recipe:

```
python3 scripts/prepare_media_decoder.py \
  --ndk "$ANDROID_HOME/ndk/29.0.14206865" --output BUILD_DIRECTORY
```

The output contains `jniLibs/ABI/libmedia_decoder.so` and `manifest.json` with
input and output digests. Completed cache entries are checked before reuse.
Interrupted downloads and partial builds require inspection. The build uses
static libraries and hides their symbols behind the JNI export map. ARM builds
keep assembly enabled; x86 builds disable standalone assembly to avoid an
additional host assembler dependency. Both 8-bit and 16-bit dav1d paths are built.
The fixed source epoch and compiler prefix maps remove host build paths; the
embedded FFmpeg configuration retains the recipe with normalized paths.
Independent builds must still establish reproducibility for the shipped APK.

## JNI contract

`NativeMediaEngine` in the media sandbox package checks `Process.isIsolated()`
before loading `media_decoder`. Each process owns one session. Handles are
generated identifiers checked under a mutex; stale handles cannot address a
replacement session.

- `nativeOpen(fd, offset, length, edge)` accepts a read-only selected range up
  to 512 MiB and an output edge of 1 through 4096. Stream discovery denies all
  probe decoders. Decoder contexts are created when the first read selects the
  requested tracks.
- `nativeInfo(handle)` returns square-pixel display width and height, duration
  in microseconds, display rotation in degrees, audio rate and output channel
  count. Display width includes the selected sample aspect ratio. Missing tracks
  use zero dimensions or rate/channels; unknown duration is zero. Rotation stays
  separate: the caller applies it to the returned pixels.
- `nativeRead(handle, videoBuffer, audioBuffer)` accepts direct buffers. A null
  buffer excludes that track before packet decode. The first read fixes track
  selection for the session. Each reply is `[kind, width, height, sampleRate,
  channels, ptsUs, byteCount]`: kind 1 is RGBA, kind 2 is little-endian PCM16,
  and kind 0 is EOF with the remaining fields zero. The caller supplies up to
  64 MiB for pixels and 262144 bytes for audio.
- `nativeSeek(handle, timeUs)` seeks in the common media timeline and flushes
  packet, decoder and conversion state. Returned keyframe preroll can precede
  the target. Playback drops preceding video and trims PCM by sample count.
- `nativeClose(handle)` releases the session; closing a stale handle is harmless.

The timeline uses one `format.start_time` origin for both tracks, retaining their
relative offset. Negative preroll down to ten seconds is preserved. Timestamps
outside that lower bound or beyond 24 hours are refused. FFmpeg's default
decoder handling of skip-sample metadata remains enabled.

## Resource and compatibility limits

Source dimensions retain the 32768-edge and 200-million-pixel admission bounds.
A requested video decoder additionally caps its surface at 16777216 pixels
before allocation. Oversized decode surfaces produce a resource refusal instead
of a smaller hidden decode. Decoder threads are fixed at one and dav1d frame
delay at one. The 64 MiB per-allocation ceiling applies to FFmpeg's allocation API.
This is not a process-wide or aggregate native-memory limit: dav1d internal
allocations and runtime overhead are separate, and reference frames can
consume more in aggregate.

Playback lookahead retains at most 8 MiB and 512 compressed packets in total,
with one pending decoded frame per track. A read selects the earlier timestamp
after both active tracks have a frame or have ended. Outputs use backpressure
through the caller's buffers. The decoder drains both tracks at EOF and resets
their state on seek. Unsupported codecs, changed audio formats, malformed inputs
and exhausted work budgets raise bounded `IOException` reasons.

Selected audio accepts 8 through 96 kHz and up to eight input channels, then
produces one or two PCM channels at the source rate.

## Display geometry and color

Video geometry validates the declared container sample aspect ratio before
FFmpeg can normalize it. Only the canonical unknown ratio, 0:1, selects frame
or codec-parameter metadata instead. The selected raw ratio must be positive or
canonically unknown; malformed declarations produce a typed refusal. A ratio
that remains unknown is treated as 1:1. The reported display width rounds coded
width times the selected ratio. Both display dimensions remain subject to the existing
32768-edge and 200-million-pixel metadata bounds. Invalid or extreme display
geometry produces a typed refusal. Scalar metadata validates every reported
track before read-time selection, so malformed video aspect metadata can also
refuse audio-only extraction from a mixed file; it never substitutes coded
geometry to hide that refusal.

Returned RGBA pixels bake that display aspect into square pixels and preserve
the complete field of view. The requested edge is an upper bound. Conversion
textures additionally fit within 8388608 pixels and the 64 MiB allocation
ceiling, including worst-case 64-pixel internal width alignment, 32-row alignment and padding for
internal RGBA64 color conversion. Common 3840-by-2160 textures remain full
size when the requested edge allows them. Larger textures reduce proportionally;
metadata retains the original display geometry. Rotation remains a separate
scalar throughout.

Fresh dynamic libswscale contexts use its stable backend, one thread and explicit
perceptual intent. When dimensions change, the first context resizes in the
source pixel encoding with its color properties and HDR side data retained.
Only then does the second context map that bounded image into full-range RGBA
with BT.709 primaries and the sRGB transfer function. Its destination carries
no source HDR mastering metadata. Aligned native frame buffers keep conversion
padding private; only packed RGBA row bytes enter the shared output.

Unsupported matrix or transfer metadata is refused. HDR inputs require explicit
matrix, primaries and transfer metadata. Compatible Dolby Vision profile-8 PQ
or HLG base layers require their matching compatibility identifier and explicit
BT.2020 non-constant-luminance encoding; enhancement-layer and RPU rendering
are outside this path. Profile 5, IPT matrices and unsupported configurations
produce typed refusals. These source contracts and host arithmetic fixtures do
not establish tone-map appearance, format fidelity or device performance;
reference images and exact-APK execution must qualify those results.

Each call has a 1500 ms cooperative interrupt deadline and a finite packet-work
limit. Native codec work can ignore cooperative interruption. The owning isolated
service must retain its independent hard watchdog, callback revocation and exact
process-death barrier. `nativeClose` cannot interrupt a call holding the JNI
mutex. Host fixture tests validate build guards and recipes; Android compilation,
format fidelity, seek, lifecycle and resource recovery require exact-APK CI tests.

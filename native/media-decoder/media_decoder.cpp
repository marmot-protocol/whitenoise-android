#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

// This filter stacks restrictions on Android's existing filter; it never replaces it.
#include <cstddef>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include "seccomp_filter.h"

static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "media sandbox requires LE syscall arguments");
static_assert(offsetof(seccomp_data, nr) == 0 && offsetof(seccomp_data, arch) == 4 &&
              offsetof(seccomp_data, args) == 16 && sizeof(((seccomp_data*)nullptr)->args[0]) == 8,
              "seccomp syscall data layout changed");
static_assert(__NR_clone == kSandboxClone && __NR_clone3 == kSandboxClone3 &&
              __NR_execve == kSandboxExecve && __NR_execveat == kSandboxExecveat &&
              __NR_seccomp == kSandboxSeccomp, "NDK syscall numbers differ from the emitted filter");
static_assert((BPF_LD | BPF_W | BPF_ABS) == 0x20 && (BPF_JMP | BPF_JEQ | BPF_K) == 0x15 &&
              (BPF_JMP | BPF_JSET | BPF_K) == 0x45 && (BPF_ALU | BPF_AND | BPF_K) == 0x54 &&
              (BPF_RET | BPF_K) == 0x06, "classic BPF instruction encoding changed");
static_assert((CLONE_THREAD | CLONE_VM | CLONE_SIGHAND) == 0x10900, "NDK clone thread mask changed");
static_assert(SECCOMP_FILTER_FLAG_TSYNC == 1 && SECCOMP_SET_MODE_FILTER == 1 &&
              SECCOMP_RET_ALLOW == 0x7fff0000U && SECCOMP_RET_ERRNO == 0x00050000U &&
              SECCOMP_RET_KILL_PROCESS == 0x80000000U && EPERM == 1, "seccomp action ABI changed");
#if defined(__aarch64__)
static_assert(AUDIT_ARCH_AARCH64 == kSandboxAuditArch, "unexpected AArch64 audit architecture");
#elif defined(__arm__)
static_assert(AUDIT_ARCH_ARM == kSandboxAuditArch, "unexpected ARM audit architecture");
#elif defined(__i386__)
static_assert(AUDIT_ARCH_I386 == kSandboxAuditArch, "unexpected x86 audit architecture");
#elif defined(__x86_64__)
static_assert(AUDIT_ARCH_X86_64 == kSandboxAuditArch, "unexpected x86_64 audit architecture");
static_assert(__X32_SYSCALL_BIT == 0x40000000, "x32 syscall marker changed");
#endif
#ifdef __NR_fork
static_assert(__NR_fork == kSandboxFork, "NDK fork number differs from emitted filter");
#else
static_assert(kSandboxFork == -1, "missing NDK fork syscall declaration");
#endif
#ifdef __NR_vfork
static_assert(__NR_vfork == kSandboxVfork, "NDK vfork number differs from emitted filter");
#else
static_assert(kSandboxVfork == -1, "missing NDK vfork syscall declaration");
#endif

namespace {
std::atomic<bool> sandboxInstalled{false};
std::atomic<unsigned> diagnosticCalls{0};

bool reserveDiagnosticCall() {
    unsigned calls = diagnosticCalls.load();
    while (calls < 8 && !diagnosticCalls.compare_exchange_weak(calls, calls + 1)) {}
    return calls < 8;
}

bool installProcessRestriction() {
    if (prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) != 0) return false;
    sock_fprog program{static_cast<unsigned short>(sizeof(kMediaProcessFilter) / sizeof(sock_filter)),
                       const_cast<sock_filter*>(kMediaProcessFilter)};
    // A positive offending TID is failure too. Never fall back to a thread-local filter.
    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program) != 0) return false;
    sandboxInstalled.store(true, std::memory_order_release);
    return true;
}

int deniedExecErrno() {
    errno = 0;
    const long result = syscall(__NR_execve, nullptr, nullptr, nullptr);
    return result == -1 ? errno : 0;
}

struct SandboxThreadStatus {
    long noNewPrivileges = -1;
    long mode = -1;
    long execErrno = -1;
};

void* collectSandboxThreadStatus(void* opaque) {
    auto* status = static_cast<SandboxThreadStatus*>(opaque);
    status->noNewPrivileges = prctl(PR_GET_NO_NEW_PRIVS, 0L, 0L, 0L, 0L);
    status->mode = prctl(PR_GET_SECCOMP, 0L, 0L, 0L, 0L);
    status->execErrno = deniedExecErrno();
    return nullptr;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return installProcessRestriction() ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/cpu.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/pixdesc.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace {
constexpr int64_t kMaxSourceBytes = 512LL * 1024 * 1024;
constexpr int64_t kMaxPixels = 16777216;
constexpr int64_t kMaxSourcePixels = 200000000;
constexpr int kMaxDimension = 32768;
constexpr int64_t kMaxConversionPixels = 8388608;
constexpr int kConversionAlignment = 64;
constexpr int64_t kConversionPadding = 1024;
constexpr int kMaxSamples = 65536;
constexpr size_t kMaxAllocation = 64U * 1024 * 1024;
constexpr int kIoBytes = 32768;
constexpr int kMaxReadSteps = 8192;
constexpr int64_t kCallMs = 1500;

int64_t monotonicMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct Failure : std::runtime_error {
    explicit Failure(const char* reason) : std::runtime_error(reason) {}
};

void check(bool condition, const char* reason) {
    if (!condition) throw Failure(reason);
}

struct Track {
    int index = -1;
    AVCodecContext* codec = nullptr;
    bool flushed = false;
    bool ended = false;
    bool hasFrame = false;
    AVFrame* frame = nullptr;
    std::deque<AVPacket*> packets;
    ~Track() {
        for (auto* packet : packets) av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codec);
    }
};

struct Session {
    int fd = -1;
    int64_t offset = 0;
    int64_t length = 0;
    int64_t position = 0;
    int64_t deadline = 0;
    int edge = 0;
    AVFormatContext* format = nullptr;
    AVIOContext* io = nullptr;
    AVPacket* packet = nullptr;
    size_t queuedBytes = 0;
    size_t queuedPackets = 0;
    Track video;
    Track audio;
    bool inputEnded = false;
    bool modeChosen = false;
    bool wantVideo = false;
    bool wantAudio = false;
    SwsContext* scale = nullptr;
    SwsContext* colors = nullptr;
    SwrContext* resample = nullptr;
    AVChannelLayout resampleInput{};
    int resampleRate = 0;
    int resampleFormat = -1;
    int outputChannels = 0;
    int outputRate = 0;
    bool resampleDrained = false;
    int64_t lastAudioPts = 0;
    bool playbackAudioClock = false;
    int64_t playbackSeekTargetUs = 0;
    int64_t playbackAudioEndSample = 0;
    ~Session() {
        sws_free_context(&scale);
        sws_free_context(&colors);
        swr_free(&resample);
        av_channel_layout_uninit(&resampleInput);
        av_packet_free(&packet);
        avformat_close_input(&format);
        if (io) {
            av_freep(&io->buffer);
            avio_context_free(&io);
        }
        if (fd >= 0) close(fd);
    }
};

std::mutex sessionMutex;
std::unique_ptr<Session> active;
int64_t activeHandle = 0;
uint64_t nextHandle = 1;

int interrupted(void* opaque) {
    return monotonicMs() >= static_cast<Session*>(opaque)->deadline;
}

int readSelected(void* opaque, uint8_t* destination, int count) {
    auto& s = *static_cast<Session*>(opaque);
    if (interrupted(&s)) return AVERROR_EXIT;
    if (count <= 0 || s.position < 0 || s.position > s.length) return AVERROR(EINVAL);
    const auto left = s.length - s.position;
    if (left == 0) return AVERROR_EOF;
    const auto wanted = static_cast<size_t>(std::min<int64_t>(count, left));
    ssize_t got;
    do {
        got = pread(s.fd, destination, wanted, s.offset + s.position);
    } while (got < 0 && errno == EINTR && !interrupted(&s));
    if (got < 0) return AVERROR(errno);
    if (got == 0) return AVERROR_EOF;
    s.position += got;
    return static_cast<int>(got);
}

int64_t seekSelected(void* opaque, int64_t offset, int whence) {
    auto& s = *static_cast<Session*>(opaque);
    if (interrupted(&s)) return AVERROR_EXIT;
    if (whence == AVSEEK_SIZE) return s.length;
    whence &= ~AVSEEK_FORCE;
    int64_t base;
    if (whence == SEEK_SET) base = 0;
    else if (whence == SEEK_CUR) base = s.position;
    else if (whence == SEEK_END) base = s.length;
    else return AVERROR(EINVAL);
    if ((offset > 0 && base > std::numeric_limits<int64_t>::max() - offset) ||
        (offset < 0 && offset < -base)) return AVERROR(EINVAL);
    const auto selected = base + offset;
    if (selected < 0 || selected > s.length) return AVERROR(EINVAL);
    s.position = selected;
    return selected;
}

int refuseSecondary(AVFormatContext*, AVIOContext**, const char*, int, AVDictionary**) {
    return AVERROR(EACCES);
}

bool allowedVideo(AVCodecID id) {
    return id == AV_CODEC_ID_H264 || id == AV_CODEC_ID_HEVC || id == AV_CODEC_ID_VP8 ||
        id == AV_CODEC_ID_VP9 || id == AV_CODEC_ID_AV1;
}

bool allowedAudio(AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_AAC: case AV_CODEC_ID_OPUS: case AV_CODEC_ID_VORBIS:
        case AV_CODEC_ID_MP3: case AV_CODEC_ID_FLAC: case AV_CODEC_ID_AMR_NB:
        case AV_CODEC_ID_AMR_WB: case AV_CODEC_ID_PCM_ALAW: case AV_CODEC_ID_PCM_MULAW:
        case AV_CODEC_ID_PCM_U8: case AV_CODEC_ID_PCM_S8:
        case AV_CODEC_ID_PCM_S16LE: case AV_CODEC_ID_PCM_S16BE:
        case AV_CODEC_ID_PCM_S24LE: case AV_CODEC_ID_PCM_S24BE:
        case AV_CODEC_ID_PCM_S32LE: case AV_CODEC_ID_PCM_S32BE:
        case AV_CODEC_ID_PCM_F32LE: case AV_CODEC_ID_PCM_F32BE:
        case AV_CODEC_ID_PCM_F64LE: case AV_CODEC_ID_PCM_F64BE: return true;
        default: return false;
    }
}

void selectTrack(Session& s, Track& track, AVMediaType type) {
    int best = av_find_best_stream(s.format, type, -1, -1, nullptr, 0);
    if (best < 0) return;
    if (type == AVMEDIA_TYPE_VIDEO && (s.format->streams[best]->disposition & AV_DISPOSITION_ATTACHED_PIC)) return;
    track.index = best;
}

void openTrack(Session& s, Track& track, AVMediaType type) {
    if (track.index < 0 || track.codec) return;
    auto* parameters = s.format->streams[track.index]->codecpar;
    check(type == AVMEDIA_TYPE_VIDEO ? allowedVideo(parameters->codec_id) : allowedAudio(parameters->codec_id),
          "unsupported_codec");
    const AVCodec* decoder = parameters->codec_id == AV_CODEC_ID_AV1 ?
        avcodec_find_decoder_by_name("libdav1d") : avcodec_find_decoder(parameters->codec_id);
    check(decoder, "unsupported_decoder");
    if (type == AVMEDIA_TYPE_VIDEO) {
        check(parameters->width > 0 && parameters->height > 0 && parameters->width <= kMaxDimension &&
              parameters->height <= kMaxDimension && int64_t(parameters->width) * parameters->height <= kMaxSourcePixels,
              "source_dimensions_refused");
        check(int64_t(parameters->width) * parameters->height <= kMaxPixels, "decode_surface_budget_refused");
    } else {
        check(parameters->sample_rate >= 8000 && parameters->sample_rate <= 96000 &&
              parameters->ch_layout.nb_channels > 0 && parameters->ch_layout.nb_channels <= 8,
              "audio_format_budget_refused");
    }
    track.codec = avcodec_alloc_context3(decoder);
    check(track.codec, "decoder_allocation_refused");
    check(avcodec_parameters_to_context(track.codec, parameters) >= 0, "invalid_codec_parameters");
    track.codec->thread_count = 1;
    track.codec->thread_type = 0;
    track.codec->max_pixels = kMaxPixels;
    track.codec->max_samples = kMaxSamples * 8LL;
    AVDictionary* options = nullptr;
    if (parameters->codec_id == AV_CODEC_ID_AV1) av_dict_set(&options, "max_frame_delay", "1", 0);
    const int opened = avcodec_open2(track.codec, decoder, &options);
    av_dict_free(&options);
    check(opened >= 0, "decoder_open_failed");
    track.frame = av_frame_alloc();
    check(track.frame, "track_frame_allocation_refused");
}

Session& session(jlong handle) {
    check(active && handle > 0 && activeHandle == handle, "stale_media_session");
    active->deadline = monotonicMs() + kCallMs;
    return *active;
}

jlongArray longs(JNIEnv* env, std::initializer_list<jlong> values) {
    auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result) env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.begin());
    return result;
}

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        auto type = env->FindClass("java/io/IOException");
        if (type) env->ThrowNew(type, message);
    }
}

int64_t timestamp(Session& s, Track& track, AVFrame* frame) {
    if (frame->best_effort_timestamp == AV_NOPTS_VALUE) return 0;
    const auto time = av_rescale_q(frame->best_effort_timestamp, s.format->streams[track.index]->time_base, AV_TIME_BASE_Q);
    const int64_t origin = s.format->start_time == AV_NOPTS_VALUE ? 0 : s.format->start_time;
    check(!((origin > 0 && time < INT64_MIN + origin) || (origin < 0 && time > INT64_MAX + origin)),
          "media_timestamp_overflow");
    const int64_t relative = time - origin;
    check(relative >= -10000000LL && relative <= 86400000000LL, "media_timestamp_range_refused");
    return relative;
}

struct DisplayGeometry {
    int width;
    int height;
};

DisplayGeometry displayGeometry(Session& s, const AVFrame* frame) {
    auto* stream = s.format->streams[s.video.index];
    const int width = frame ? frame->width : stream->codecpar->width;
    const int height = frame ? frame->height : stream->codecpar->height;
    check(width > 0 && height > 0 && width <= kMaxDimension && height <= kMaxDimension &&
          int64_t(width) * height <= kMaxSourcePixels, "source_dimensions_refused");
    AVRational rawSar = stream->sample_aspect_ratio;
    if (rawSar.num == 0 && rawSar.den == 1) {
        rawSar = frame ? frame->sample_aspect_ratio : stream->codecpar->sample_aspect_ratio;
    }
    // FFmpeg normalizes malformed ratios into unknown. Reject the selected raw declaration before that step.
    check((rawSar.num == 0 && rawSar.den == 1) || (rawSar.num > 0 && rawSar.den > 0),
          "sample_aspect_ratio_refused");
    AVRational sar = av_guess_sample_aspect_ratio(s.format, stream, const_cast<AVFrame*>(frame));
    if (sar.num == 0 && sar.den == 1) sar = {1, 1};
    check(sar.num > 0 && sar.den > 0, "sample_aspect_ratio_refused");
    // Positive int32 SAR and admitted coded width keep this arithmetic inside int64.
    const int64_t displayWidth = (int64_t(width) * sar.num + sar.den / 2) / sar.den;
    check(displayWidth > 0 && displayWidth <= kMaxDimension &&
          displayWidth * height <= kMaxSourcePixels, "display_dimensions_refused");
    return {static_cast<int>(displayWidth), height};
}

DisplayGeometry conversionGeometry(DisplayGeometry display, int edge) {
    const int longest = std::max(display.width, display.height);
    int low = 1, high = std::min(edge, longest);
    DisplayGeometry fitted{1, 1};
    // Search the bounded edge range. Preserve the complete display aspect, never crop to the budget.
    while (low <= high) {
        const int candidate = low + (high - low) / 2;
        const int width = std::max(1, int(int64_t(display.width) * candidate / longest));
        const int height = std::max(1, int(int64_t(display.height) * candidate / longest));
        const int64_t alignedWidth = (int64_t(width) + 63) / 64 * 64;
        const int64_t alignedHeight = (int64_t(height) + 31) / 32 * 32;
        // RGBA64 CMS intermediates need eight bytes/pixel. Include AVFrame plane padding and allocation guards.
        const int64_t footprint = alignedWidth * alignedHeight * 8 + kConversionPadding;
        if (int64_t(width) * height <= kMaxConversionPixels && footprint < int64_t(kMaxAllocation)) {
            fitted = {width, height};
            low = candidate + 1;
        } else {
            high = candidate - 1;
        }
    }
    return fitted;
}

void validateVideoColor(Session& s, const AVFrame* frame) {
    check(sws_test_colorspace(frame->colorspace, 0) && sws_test_transfer(frame->color_trc, 0) &&
          sws_test_primaries(frame->color_primaries, 0), "unsupported_video_color_metadata");
    const bool hdr = frame->color_trc == AVCOL_TRC_SMPTE2084 || frame->color_trc == AVCOL_TRC_ARIB_STD_B67;
    const bool hdrMetadata = av_frame_get_side_data(frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA) ||
                             av_frame_get_side_data(frame, AV_FRAME_DATA_DYNAMIC_HDR_PLUS);
    if (hdr || hdrMetadata) {
        check(frame->colorspace != AVCOL_SPC_UNSPECIFIED && frame->color_primaries != AVCOL_PRI_UNSPECIFIED &&
              frame->color_trc != AVCOL_TRC_UNSPECIFIED, "underspecified_hdr_color_metadata");
    }
    auto* parameters = s.format->streams[s.video.index]->codecpar;
    const auto* side = av_packet_side_data_get(parameters->coded_side_data, parameters->nb_coded_side_data,
                                               AV_PKT_DATA_DOVI_CONF);
    const bool doviFrame = av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_RPU_BUFFER) ||
                           av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_METADATA);
    if (side || doviFrame) {
        check(side && side->size >= sizeof(AVDOVIDecoderConfigurationRecord), "unsupported_dolby_vision_profile");
        const auto* config = reinterpret_cast<const AVDOVIDecoderConfigurationRecord*>(side->data);
        const bool pqBase = config->dv_bl_signal_compatibility_id == 1 && frame->color_trc == AVCOL_TRC_SMPTE2084;
        const bool hlgBase = config->dv_bl_signal_compatibility_id == 4 && frame->color_trc == AVCOL_TRC_ARIB_STD_B67;
        check(config->dv_profile == 8 && config->bl_present_flag && !config->el_present_flag &&
              (pqBase || hlgBase) && frame->colorspace == AVCOL_SPC_BT2020_NCL &&
              frame->color_primaries == AVCOL_PRI_BT2020, "unsupported_dolby_vision_profile");
        // Compatible profile-8 PQ/HLG base only: no Dolby Vision enhancement/RPU rendering claim.
    }
}

SwsContext* dynamicScaler(SwsContext*& context) {
    if (!context) {
        context = sws_alloc_context();
        check(context, "pixel_context_allocation_refused");
        context->threads = 1;
        context->scaler = SWS_SCALE_BILINEAR;
        context->intent = SWS_INTENT_PERCEPTUAL;
        context->backends = SWS_BACKEND_STABLE;
    }
    return context;
}

struct ConversionFrames {
    AVFrame* scaled = av_frame_alloc();
    AVFrame* rgba = av_frame_alloc();
    ~ConversionFrames() { av_frame_free(&scaled); av_frame_free(&rgba); }
};

jlongArray video(JNIEnv* env, Session& s, jobject destination) {
    auto* frame = s.video.frame;
    check(frame->width > 0 && frame->height > 0 && frame->width <= kMaxDimension && frame->height <= kMaxDimension &&
          int64_t(frame->width) * frame->height <= kMaxPixels, "decoded_surface_budget_refused");
    validateVideoColor(s, frame);
    const auto* descriptor = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));
    check(descriptor && av_get_padded_bits_per_pixel(descriptor) <= 64 && sws_test_frame(frame, 0) &&
          sws_test_format(static_cast<AVPixelFormat>(frame->format), 1), "unsupported_video_pixel_format");
    check(av_cpu_max_align() <= kConversionAlignment, "color_alignment_refused");
    const auto display = displayGeometry(s, frame);
    const auto fitted = conversionGeometry(display, s.edge);
    const int width = fitted.width, height = fitted.height;
    const int64_t bytes = int64_t(width) * height * 4;
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    check(out && env->GetDirectBufferCapacity(destination) >= bytes && bytes <= int64_t(kMaxAllocation),
          "video_output_capacity_refused");
    ConversionFrames converted;
    check(converted.scaled && converted.rgba, "pixel_frame_allocation_refused");
    const AVFrame* colorSource = frame;
    if (frame->width != width || frame->height != height) {
        // Downscale in the unchanged source encoding first: CMS otherwise allocates at full source dimensions.
        check(av_frame_copy_props(converted.scaled, frame) >= 0, "pixel_properties_allocation_refused");
        converted.scaled->format = frame->format;
        converted.scaled->width = width; converted.scaled->height = height;
        converted.scaled->sample_aspect_ratio = {1, 1};
        converted.scaled->crop_left = converted.scaled->crop_right = 0;
        converted.scaled->crop_top = converted.scaled->crop_bottom = 0;
        check(av_frame_get_buffer(converted.scaled, kConversionAlignment) >= 0, "pixel_scale_allocation_refused");
        check(sws_scale_frame(dynamicScaler(s.scale), converted.scaled, frame) >= 0, "pixel_scale_failed");
        colorSource = converted.scaled;
    }
    // Do not copy source HDR side data onto this SDR destination.
    converted.rgba->format = AV_PIX_FMT_RGBA;
    converted.rgba->width = width; converted.rgba->height = height;
    converted.rgba->sample_aspect_ratio = {1, 1};
    converted.rgba->color_range = AVCOL_RANGE_JPEG;
    converted.rgba->colorspace = AVCOL_SPC_RGB;
    converted.rgba->color_primaries = AVCOL_PRI_BT709;
    converted.rgba->color_trc = AVCOL_TRC_IEC61966_2_1;
    converted.rgba->flags = frame->flags & (AV_FRAME_FLAG_INTERLACED | AV_FRAME_FLAG_TOP_FIELD_FIRST);
    check(av_frame_get_buffer(converted.rgba, kConversionAlignment) >= 0, "pixel_rgba_allocation_refused");
    check(sws_scale_frame(dynamicScaler(s.colors), converted.rgba, colorSource) >= 0, "pixel_color_conversion_failed");
    // Copy only packed pixel bytes; SIMD/aligned AVFrame padding never crosses the shared-output boundary.
    for (int row = 0; row < height; ++row) {
        std::memcpy(out + int64_t(row) * width * 4,
                    converted.rgba->data[0] + int64_t(row) * converted.rgba->linesize[0], size_t(width) * 4);
    }
    return longs(env, {1, width, height, 0, 0, timestamp(s, s.video, frame), bytes});
}

void configureResampler(Session& s) {
    auto* frame = s.audio.frame;
    check(frame->sample_rate >= 8000 && frame->sample_rate <= 96000 && frame->ch_layout.nb_channels > 0 &&
          frame->ch_layout.nb_channels <= 8 && frame->nb_samples > 0 && frame->nb_samples <= kMaxSamples,
          "decoded_audio_budget_refused");
    if (s.resample) {
        check(frame->sample_rate == s.resampleRate && frame->format == s.resampleFormat &&
              av_channel_layout_compare(&frame->ch_layout, &s.resampleInput) == 0,
              "audio_format_change_refused");
        return;
    }
    s.outputRate = frame->sample_rate;
    s.outputChannels = std::min(2, frame->ch_layout.nb_channels);
    AVChannelLayout output{};
    av_channel_layout_default(&output, s.outputChannels);
    const int configured = swr_alloc_set_opts2(&s.resample, &output, AV_SAMPLE_FMT_S16, s.outputRate,
                                              &frame->ch_layout, static_cast<AVSampleFormat>(frame->format),
                                              frame->sample_rate, 0, nullptr);
    av_channel_layout_uninit(&output);
    check(configured >= 0 && s.resample && swr_init(s.resample) >= 0, "audio_conversion_refused");
    check(av_channel_layout_copy(&s.resampleInput, &frame->ch_layout) >= 0, "audio_layout_refused");
    s.resampleRate = frame->sample_rate;
    s.resampleFormat = frame->format;
}

bool playbackPaddingEnabled(const Session& s) {
    // Waveforms/transcription and audio-only playback retain their original PCM.
    return s.wantVideo && s.wantAudio && s.video.codec && s.audio.codec;
}

void initializePlaybackAudioClock(Session& s) {
    if (s.playbackAudioClock || !playbackPaddingEnabled(s)) return;
    if (s.audio.hasFrame) {
        configureResampler(s);
    } else if (!s.resample) {
        // An empty audio track can reach EOF without producing a frame. Its
        // declared format still supplies a bounded silent playback clock.
        s.outputRate = s.audio.codec->sample_rate;
        s.outputChannels = std::min(2, s.audio.codec->ch_layout.nb_channels);
    }
    check(s.outputRate >= 8000 && s.outputRate <= 96000 && s.outputChannels >= 1 && s.outputChannels <= 2,
          "playback_clock_format_refused");
    s.playbackAudioEndSample = av_rescale_rnd(s.playbackSeekTargetUs, s.outputRate, AV_TIME_BASE, AV_ROUND_UP);
    s.playbackAudioClock = true;
}

void recordPlaybackAudio(Session& s, int64_t ptsUs, int frames) {
    if (!playbackPaddingEnabled(s)) return;
    initializePlaybackAudioClock(s);
    const int64_t start = av_rescale_q(ptsUs, AV_TIME_BASE_Q, AVRational{1, s.outputRate});
    // A preroll frame can begin before seekTarget; the parent trims its prefix.
    // Such a frame must never move the synthetic clock behind the seek target.
    s.playbackAudioEndSample = std::max(s.playbackAudioEndSample, start + frames);
}

jlongArray playbackPadding(JNIEnv* env, Session& s, jobject destination, int64_t targetUs) {
    if (!playbackPaddingEnabled(s)) return nullptr;
    initializePlaybackAudioClock(s);
    // End padding at or before the next real audio sample/event. Work in sample
    // units so microsecond timestamp rounding cannot insert a sample per frame.
    const int64_t target = av_rescale_rnd(targetUs, s.outputRate, AV_TIME_BASE, AV_ROUND_DOWN);
    if (target <= s.playbackAudioEndSample) return nullptr;
    const int chunkFrames = std::min(kMaxSamples, s.outputRate / 20); // At most 50 ms of queued silence.
    const int frames = static_cast<int>(std::min<int64_t>(chunkFrames, target - s.playbackAudioEndSample));
    const int bytes = frames * s.outputChannels * 2;
    auto* output = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    check(output && env->GetDirectBufferCapacity(destination) >= bytes, "audio_padding_capacity_refused");
    std::memset(output, 0, static_cast<size_t>(bytes));
    const int64_t pts = av_rescale_q(s.playbackAudioEndSample, AVRational{1, s.outputRate}, AV_TIME_BASE_Q);
    s.playbackAudioEndSample += frames;
    return longs(env, {2, 0, 0, s.outputRate, s.outputChannels, pts, bytes});
}

jlongArray audio(JNIEnv* env, Session& s, jobject destination, bool draining) {
    if (!draining) configureResampler(s);
    if (!s.resample) return nullptr;
    const int samples = swr_get_out_samples(s.resample, draining ? 0 : s.audio.frame->nb_samples);
    check(samples >= 0 && samples <= kMaxSamples, "audio_output_budget_refused");
    const int64_t capacity = int64_t(samples) * s.outputChannels * 2;
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    check(out && env->GetDirectBufferCapacity(destination) >= capacity, "audio_output_capacity_refused");
    uint8_t* planes[] = {out};
    const uint8_t* input[8] = {};
    if (!draining) {
        const int planeCount = av_sample_fmt_is_planar(static_cast<AVSampleFormat>(s.audio.frame->format)) ?
            s.audio.frame->ch_layout.nb_channels : 1;
        for (int i = 0; i < planeCount; ++i) input[i] = s.audio.frame->extended_data[i];
    }
    const int produced = swr_convert(s.resample, planes, samples,
                                    draining ? nullptr : input,
                                    draining ? 0 : s.audio.frame->nb_samples);
    check(produced >= 0, "audio_conversion_failed");
    if (produced == 0) return nullptr;
    const int64_t pts = draining ? s.lastAudioPts : timestamp(s, s.audio, s.audio.frame);
    s.lastAudioPts = pts + av_rescale(produced, AV_TIME_BASE, s.outputRate);
    recordPlaybackAudio(s, pts, produced);
    return longs(env, {2, 0, 0, s.outputRate, s.outputChannels, pts, int64_t(produced) * s.outputChannels * 2});
}

bool pump(Session& s, Track& track) {
    if (!track.codec || track.ended || track.hasFrame) return track.hasFrame;
    for (int step = 0; step < kMaxReadSteps; ++step) {
        check(!interrupted(&s), "media_read_deadline");
        av_frame_unref(track.frame);
        const int result = avcodec_receive_frame(track.codec, track.frame);
        if (result >= 0) { track.hasFrame = true; return true; }
        if (result == AVERROR_EOF) { track.ended = true; return false; }
        check(result == AVERROR(EAGAIN), "media_decode_failed");
        if (!track.packets.empty()) {
            auto* packet = track.packets.front();
            const int sent = avcodec_send_packet(track.codec, packet);
            // receive(EAGAIN) then send(EAGAIN) violates the codec API contract.
            check(sent >= 0, "media_packet_refused");
            s.queuedBytes -= static_cast<size_t>(packet->size);
            --s.queuedPackets;
            track.packets.pop_front();
            av_packet_free(&packet);
            continue;
        }
        if (s.inputEnded && !track.flushed) {
            const int sent = avcodec_send_packet(track.codec, nullptr);
            check(sent >= 0 || sent == AVERROR_EOF, "media_drain_failed");
            track.flushed = true;
            if (sent == AVERROR_EOF) { track.ended = true; return false; }
            continue;
        }
        check(!track.flushed, "media_drain_stalled");
        return false;
    }
    throw Failure("media_decode_work_budget_refused");
}

void queuePacket(Session& s) {
    const int result = av_read_frame(s.format, s.packet);
    check(result >= 0 || result == AVERROR_EOF, "media_container_read_failed");
    if (result == AVERROR_EOF) { s.inputEnded = true; return; }
    Track* track = nullptr;
    if (s.wantVideo && s.packet->stream_index == s.video.index) track = &s.video;
    if (s.wantAudio && s.packet->stream_index == s.audio.index) track = &s.audio;
    if (track && !track->ended) {
        check(s.packet->size >= 0 && s.packet->size <= 8 * 1024 * 1024 &&
              s.queuedBytes <= 8 * 1024 * 1024 - static_cast<size_t>(s.packet->size) && s.queuedPackets < 512,
              "compressed_lookahead_budget_refused");
        auto* owned = av_packet_clone(s.packet);
        check(owned, "packet_queue_allocation_refused");
        track->packets.push_back(owned);
        s.queuedBytes += static_cast<size_t>(owned->size);
        ++s.queuedPackets;
    }
    av_packet_unref(s.packet);
}

jlongArray read(JNIEnv* env, Session& s, jobject videoOut, jobject audioOut) {
    const bool wantVideo = videoOut != nullptr;
    const bool wantAudio = audioOut != nullptr;
    check(wantVideo || wantAudio, "output_track_required");
    if (!s.modeChosen) {
        if (wantVideo) openTrack(s, s.video, AVMEDIA_TYPE_VIDEO);
        if (wantAudio) openTrack(s, s.audio, AVMEDIA_TYPE_AUDIO);
        s.modeChosen = true;
        s.wantVideo = wantVideo;
        s.wantAudio = wantAudio;
    }
    check(s.wantVideo == wantVideo && s.wantAudio == wantAudio, "output_track_selection_changed");
    for (int step = 0; step < kMaxReadSteps; ++step) {
        check(!interrupted(&s), "media_read_deadline");
        if (wantVideo) pump(s, s.video);
        if (wantAudio) pump(s, s.audio);
        const bool videoDone = !wantVideo || !s.video.codec || s.video.ended;
        const bool audioDone = !wantAudio || !s.audio.codec || s.audio.ended;
        if (wantAudio && audioDone && !s.resampleDrained) {
            // Conversion can retain real samples after decoder EOF. Emit them
            // before any synthesized gap/tail can cover their sample interval.
            auto result = audio(env, s, audioOut, true);
            if (result) return result;
            s.resampleDrained = true;
        }
        const bool videoReady = videoDone || s.video.hasFrame;
        const bool audioReady = audioDone || s.audio.hasFrame;
        // Read ahead compressed packets until each active track has one decoded
        // frame. MP4 chunk order cannot cause video to block discovery of audio.
        if (videoReady && audioReady) {
            if (s.video.hasFrame || s.audio.hasFrame) {
                const int64_t videoPts = s.video.hasFrame ? timestamp(s, s.video, s.video.frame) : INT64_MAX;
                const int64_t audioPts = s.audio.hasFrame ? timestamp(s, s.audio, s.audio.frame) : INT64_MAX;
                auto padding = playbackPadding(env, s, audioOut, std::min(videoPts, audioPts));
                if (padding) return padding;
            }
            const bool chooseVideo = s.video.hasFrame &&
                (!s.audio.hasFrame || timestamp(s, s.video, s.video.frame) <= timestamp(s, s.audio, s.audio.frame));
            if (chooseVideo) {
                auto result = video(env, s, videoOut);
                s.video.hasFrame = false;
                av_frame_unref(s.video.frame);
                return result;
            }
            if (s.audio.hasFrame) {
                auto result = audio(env, s, audioOut, false);
                s.audio.hasFrame = false;
                av_frame_unref(s.audio.frame);
                if (result) return result;
                continue;
            }
            // The last frame's display interval can extend beyond its PTS.
            // A known finite container duration keeps an audio-master parent
            // clock moving through that tail before the final EOF event.
            const int64_t duration = s.format->duration == AV_NOPTS_VALUE ? 0 :
                std::clamp<int64_t>(s.format->duration, 0, 86400000000LL);
            auto padding = playbackPadding(env, s, audioOut, duration);
            if (padding) return padding;
            return longs(env, {0, 0, 0, 0, 0, 0, 0});
        }
        check(!s.inputEnded, "media_track_drain_stalled");
        queuePacket(s);
    }
    throw Failure("media_packet_work_budget_refused");
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_ipf_whitenoise_android_media_sandbox_NativeMediaEngine_nativeOpen(
    JNIEnv* env, jobject, jint fd, jlong offset, jlong length, jint edge) {
    std::lock_guard<std::mutex> guard(sessionMutex);
    try {
        check(sandboxInstalled.load(std::memory_order_acquire), "media_process_restriction_missing");
        check(!active, "media_session_busy");
        check(fd >= 0 && offset >= 0 && length > 0 && length <= kMaxSourceBytes && edge >= 1 && edge <= 4096,
              "selected_media_bounds_refused");
        struct stat metadata{};
        check(fstat(fd, &metadata) == 0 && S_ISREG(metadata.st_mode) && offset <= metadata.st_size &&
              length <= metadata.st_size - offset, "selected_media_range_refused");
        const int flags = fcntl(fd, F_GETFL);
        check(flags >= 0 && (flags & O_ACCMODE) == O_RDONLY, "selected_media_not_readonly");
        auto s = std::make_unique<Session>();
        s->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        check(s->fd >= 0, "selected_media_dup_failed");
        s->offset = offset; s->length = length; s->edge = edge;
        s->deadline = monotonicMs() + kCallMs;
        av_max_alloc(kMaxAllocation);
        av_log_set_level(AV_LOG_QUIET);
        s->format = avformat_alloc_context();
        check(s->format, "format_allocation_refused");
        auto* buffer = static_cast<uint8_t*>(av_malloc(kIoBytes));
        check(buffer, "input_buffer_allocation_refused");
        s->io = avio_alloc_context(buffer, kIoBytes, 0, s.get(), readSelected, nullptr, seekSelected);
        if (!s->io) av_free(buffer);
        check(s->io, "input_context_allocation_refused");
        s->format->pb = s->io;
        s->format->flags |= AVFMT_FLAG_CUSTOM_IO;
        s->format->io_open = refuseSecondary;
        s->format->interrupt_callback = {interrupted, s.get()};
        s->format->probesize = 2 * 1024 * 1024;
        s->format->max_analyze_duration = 2 * AV_TIME_BASE;
        s->format->max_streams = 16;
        s->format->max_index_size = 1024 * 1024;
        s->format->max_probe_packets = 64;
        AVDictionary* options = nullptr;
        av_dict_set(&options, "format_whitelist", "mov,matroska,webm,ogg,wav,mp3,aac,flac,amr", 0);
        av_dict_set(&options, "protocol_whitelist", "", 0);
        // Stream discovery may otherwise open decoders before our surface/sample budgets.
        // An empty non-null whitelist denies every probe decoder, including late-created streams.
        av_dict_set(&options, "codec_whitelist", "", 0);
        const int opened = avformat_open_input(&s->format, nullptr, nullptr, &options);
        av_dict_free(&options);
        check(opened >= 0, "media_container_refused");
        check(avformat_find_stream_info(s->format, nullptr) >= 0, "media_stream_info_refused");
        selectTrack(*s, s->video, AVMEDIA_TYPE_VIDEO);
        selectTrack(*s, s->audio, AVMEDIA_TYPE_AUDIO);
        check(s->video.index >= 0 || s->audio.index >= 0, "no_media_track");
        s->packet = av_packet_alloc();
        check(s->packet, "media_packet_allocation_refused");
        check(nextHandle <= uint64_t(std::numeric_limits<int64_t>::max()), "media_generation_exhausted");
        activeHandle = static_cast<int64_t>(nextHandle++);
        active = std::move(s);
        return activeHandle;
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ipf_whitenoise_android_media_sandbox_NativeMediaEngine_nativeClose(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> guard(sessionMutex);
    if (active && activeHandle == handle) { active.reset(); activeHandle = 0; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ipf_whitenoise_android_media_sandbox_NativeMediaEngine_nativeInfo(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> guard(sessionMutex);
    try {
        auto& s = session(handle);
        int width = 0, height = 0, rotation = 0, rate = 0, channels = 0;
        if (s.video.index >= 0) {
            auto* stream = s.format->streams[s.video.index];
            width = stream->codecpar->width; height = stream->codecpar->height;
            if (width > 0 && height > 0) {
                const auto display = displayGeometry(s, nullptr);
                width = display.width; height = display.height;
            }
            const auto* matrix = av_packet_side_data_get(stream->codecpar->coded_side_data,
                                                         stream->codecpar->nb_coded_side_data,
                                                         AV_PKT_DATA_DISPLAYMATRIX);
            if (matrix && matrix->size >= 9 * sizeof(int32_t)) {
                const double value = -av_display_rotation_get(reinterpret_cast<const int32_t*>(matrix->data));
                if (std::isfinite(value)) rotation = (int(std::lround(value)) % 360 + 360) % 360;
            }
        }
        if (s.audio.index >= 0) {
            auto* parameters = s.format->streams[s.audio.index]->codecpar;
            rate = parameters->sample_rate; channels = std::min(2, parameters->ch_layout.nb_channels);
        }
        const auto duration = s.format->duration == AV_NOPTS_VALUE ? 0 :
            std::clamp<int64_t>(s.format->duration, 0, 86400000000LL);
        return longs(env, {width, height, duration, rotation, rate, channels});
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ipf_whitenoise_android_media_sandbox_NativeMediaEngine_nativeRead(
    JNIEnv* env, jobject, jlong handle, jobject videoOut, jobject audioOut) {
    std::lock_guard<std::mutex> guard(sessionMutex);
    try { return read(env, session(handle), videoOut, audioOut); }
    catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ipf_whitenoise_android_media_sandbox_NativeMediaEngine_nativeSeek(
    JNIEnv* env, jobject, jlong handle, jlong timeUs) {
    std::lock_guard<std::mutex> guard(sessionMutex);
    try {
        auto& s = session(handle);
        check(timeUs >= 0 && timeUs <= 86400000000LL, "seek_range_refused");
        const int64_t origin = s.format->start_time == AV_NOPTS_VALUE ? 0 : s.format->start_time;
        check(!((origin > 0 && timeUs > INT64_MAX - origin) || (origin < 0 && timeUs < INT64_MIN - origin)),
              "seek_timestamp_overflow");
        const int64_t target = timeUs + origin;
        check(avformat_seek_file(s.format, -1, INT64_MIN, target, target, 0) >= 0, "media_seek_refused");
        for (auto* track : {&s.video, &s.audio}) {
            if (track->codec) avcodec_flush_buffers(track->codec);
            track->flushed = false; track->ended = false; track->hasFrame = false;
            if (track->frame) av_frame_unref(track->frame);
            for (auto* packet : track->packets) av_packet_free(&packet);
            track->packets.clear();
        }
        av_packet_unref(s.packet);
        s.queuedBytes = 0; s.queuedPackets = 0;
        s.inputEnded = false; s.resampleDrained = false;
        swr_free(&s.resample); av_channel_layout_uninit(&s.resampleInput);
        s.resampleRate = 0; s.resampleFormat = -1; s.lastAudioPts = timeUs;
        s.playbackAudioClock = false; s.playbackSeekTargetUs = timeUs; s.playbackAudioEndSample = 0;
    } catch (const std::exception& error) { fail(env, error.what()); }
}

// Bounded diagnostics only: null exec/clone3 arguments cannot create a process even without this filter.
// Layout v1: [version, installed, callerNNP, callerMode, execErrno,
//             pthreadCreateResult, threadNNP, threadMode, threadExecErrno].
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ipf_whitenoise_android_media_sandbox_NativeMediaEngine_nativeSandboxDiagnostics(JNIEnv* env, jobject) {
    try {
        check(sandboxInstalled.load(std::memory_order_acquire), "media_process_restriction_missing");
        check(reserveDiagnosticCall(), "media_diagnostic_budget_refused");
        SandboxThreadStatus caller;
        collectSandboxThreadStatus(&caller);
        SandboxThreadStatus child;
        pthread_t thread{};
        const int created = pthread_create(&thread, nullptr, collectSandboxThreadStatus, &child);
        // A join failure cannot let a thread retain a pointer into this JNI stack.
        if (created == 0 && pthread_join(thread, nullptr) != 0) _exit(125);
        return longs(env, {1, 1, caller.noNewPrivileges, caller.mode, caller.execErrno,
                           created, child.noNewPrivileges, child.mode, child.execErrno});
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

// Only a dedicated disposable diagnostic worker calls this: an existing Android filter may SIGSYS clone3.
extern "C" JNIEXPORT jint JNICALL
Java_dev_ipf_whitenoise_android_media_sandbox_NativeMediaEngine_nativeSandboxClone3Errno(JNIEnv* env, jobject) {
    try {
        check(sandboxInstalled.load(std::memory_order_acquire), "media_process_restriction_missing");
        check(reserveDiagnosticCall(), "media_diagnostic_budget_refused");
        errno = 0;
        const long result = syscall(__NR_clone3, nullptr, 0);
        return result == -1 ? errno : 0;
    } catch (const std::exception& error) { fail(env, error.what()); return -1; }
}

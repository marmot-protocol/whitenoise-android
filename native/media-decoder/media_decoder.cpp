#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
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
    SwrContext* resample = nullptr;
    AVChannelLayout resampleInput{};
    int resampleRate = 0;
    int resampleFormat = -1;
    int outputChannels = 0;
    int outputRate = 0;
    bool resampleDrained = false;
    int64_t lastAudioPts = 0;
    ~Session() {
        sws_freeContext(scale);
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

jlongArray video(JNIEnv* env, Session& s, jobject destination) {
    auto* frame = s.video.frame;
    check(frame->width > 0 && frame->height > 0 && frame->width <= kMaxDimension && frame->height <= kMaxDimension &&
          int64_t(frame->width) * frame->height <= kMaxPixels, "decoded_surface_budget_refused");
    const double ratio = std::min(1.0, double(s.edge) / std::max(frame->width, frame->height));
    const int width = std::max(1, int(std::floor(frame->width * ratio)));
    const int height = std::max(1, int(std::floor(frame->height * ratio)));
    const int64_t bytes = int64_t(width) * height * 4;
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    check(out && env->GetDirectBufferCapacity(destination) >= bytes && bytes <= int64_t(kMaxAllocation),
          "video_output_capacity_refused");
    s.scale = sws_getCachedContext(s.scale, frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
                                 width, height, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
    check(s.scale, "pixel_conversion_refused");
    uint8_t* planes[] = {out, nullptr, nullptr, nullptr};
    int strides[] = {width * 4, 0, 0, 0};
    check(sws_scale(s.scale, frame->data, frame->linesize, 0, frame->height, planes, strides) == height,
          "pixel_conversion_failed");
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
        const bool videoReady = videoDone || s.video.hasFrame;
        const bool audioReady = audioDone || s.audio.hasFrame;
        // Read ahead compressed packets until each active track has one decoded
        // frame. MP4 chunk order cannot cause video to block discovery of audio.
        if (videoReady && audioReady) {
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
            if (wantAudio && !s.resampleDrained) {
                auto result = audio(env, s, audioOut, true);
                if (result) return result;
                s.resampleDrained = true;
            }
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
    } catch (const std::exception& error) { fail(env, error.what()); }
}

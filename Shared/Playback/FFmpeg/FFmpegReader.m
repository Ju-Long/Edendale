#import "FFmpegReader.h"
#import "FFmpegBridge.h"
#import <FFmpeg/FFmpeg.h>
#import <FFmpeg/libavutil/pixdesc.h>
#import <time.h>
#import <stdatomic.h>

@implementation EDFFmpegFrame
- (instancetype)initWithPixelBuffer:(CVPixelBufferRef)pixelBuffer
                             audio:(CMSampleBufferRef)audio
                              time:(double)time duration:(double)duration {
    if ((self = [super init])) {
        _pixelBuffer = pixelBuffer ? CVPixelBufferRetain(pixelBuffer) : NULL;
        _audioSampleBuffer = audio ? (CMSampleBufferRef)CFRetain(audio) : NULL;
        _presentationTime = time;
        _duration = duration;
    }
    return self;
}
- (void)dealloc {
    if (_pixelBuffer) CVPixelBufferRelease(_pixelBuffer);
    if (_audioSampleBuffer) CFRelease(_audioSampleBuffer);
}
@end

@implementation EDFFmpegReader {
    AVFormatContext *_format;
    AVCodecContext *_video;
    AVCodecContext *_audio;
    AVPacket *_packet;
    AVFrame *_frame;
    struct SwsContext *_scaler;
    SwrContext *_resampler;
    AVChannelLayout _inputLayout;
    int _inputRate;
    enum AVSampleFormat _inputFormat;
    int _videoIndex;
    int _audioIndex;
    BOOL _hardwareDecoding;
    BOOL _drained;
    double _origin;
    double _seekFloor;
    double _videoNextTime;
    double _audioNextTime;
    double _frameDuration;
    atomic_bool _interrupted;
    atomic_int_fast64_t _deadline;
}

static BOOL EDReaderError(NSError **error, NSString *operation, int code) {
    if (error) {
        *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:code userInfo:@{
            NSLocalizedDescriptionKey: [NSString stringWithFormat:@"%@: %@", operation, edendale_av_err2str(code)]
        }];
    }
    return NO;
}

static int EDInterrupt(void *opaque) {
    EDFFmpegReader *reader = (__bridge EDFFmpegReader *)opaque;
    return atomic_load(&reader->_interrupted) ||
        (atomic_load(&reader->_deadline) > 0 && (int64_t)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) > atomic_load(&reader->_deadline));
}

- (instancetype)initWithHardwareDecoding:(BOOL)hardwareDecoding {
    if ((self = [super init])) {
        _hardwareDecoding = hardwareDecoding;
        _videoIndex = _audioIndex = -1;
        _mediaInfo = @{};
        atomic_init(&_interrupted, false);
        atomic_init(&_deadline, 0);
    }
    return self;
}

- (void)interrupt { atomic_store(&_interrupted, true); }
- (BOOL)atEnd { return _drained; }

- (void)close {
    avcodec_free_context(&_video);
    avcodec_free_context(&_audio);
    avformat_close_input(&_format);
    av_packet_free(&_packet);
    av_frame_free(&_frame);
    sws_freeContext(_scaler);
    _scaler = NULL;
    swr_free(&_resampler);
    av_channel_layout_uninit(&_inputLayout);
    _videoIndex = _audioIndex = -1;
    _mediaInfo = @{};
}

- (void)dealloc { [self close]; }

- (AVCodecContext *)openCodec:(int)index hardware:(BOOL)hardware error:(NSError **)error {
    AVCodecParameters *parameters = _format->streams[index]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(parameters->codec_id);
    if (!codec) {
        EDReaderError(error, @"This media codec is not included in FFmpeg", AVERROR_DECODER_NOT_FOUND);
        return NULL;
    }
    AVCodecContext *context = avcodec_alloc_context3(codec);
    if (!context) { EDReaderError(error, @"Allocate decoder", AVERROR(ENOMEM)); return NULL; }
    int result = avcodec_parameters_to_context(context, parameters);
    context->pkt_timebase = _format->streams[index]->time_base;
    // Bound memory consumption for high-resolution software decoding.
    context->thread_count = 2;
    if (result >= 0 && hardware) {
        for (int i = 0; ; i++) {
            const AVCodecHWConfig *config = avcodec_get_hw_config(codec, i);
            if (!config) break;
            if (config->device_type == AV_HWDEVICE_TYPE_VIDEOTOOLBOX &&
                (config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX)) {
                // Failure leaves the decoder on its software path.
                edendale_setup_videotoolbox(context);
                break;
            }
        }
    }
    if (result >= 0) result = avcodec_open2(context, codec, NULL);
    if (result < 0) {
        avcodec_free_context(&context);
        EDReaderError(error, @"Open media decoder", result);
    }
    return context;
}

- (BOOL)openURL:(NSURL *)url error:(NSError **)error {
    [self close];
    // Do not clear interruption here: close/cancellation may precede queued open.
    if (atomic_load(&_interrupted)) return EDReaderError(error, @"Playback cancelled", AVERROR_EXIT);
    static dispatch_once_t once;
    dispatch_once(&once, ^{ avformat_network_init(); });
    _format = avformat_alloc_context();
    if (!_format) return EDReaderError(error, @"Allocate media reader", AVERROR(ENOMEM));
    _format->interrupt_callback = (AVIOInterruptCB){ EDInterrupt, (__bridge void *)self };
    atomic_store(&_deadline, (int64_t)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) + 20000000);
    const char *location = url.isFileURL ? url.fileSystemRepresentation : url.absoluteString.UTF8String;
    int result = avformat_open_input(&_format, location, NULL, NULL);
    if (result >= 0) result = avformat_find_stream_info(_format, NULL);
    atomic_store(&_deadline, 0);
    if (result < 0) { [self close]; return EDReaderError(error, @"Could not open media", result); }

    _origin = _format->start_time == AV_NOPTS_VALUE ? 0 : (double)_format->start_time / AV_TIME_BASE;
    _seekFloor = _videoNextTime = _audioNextTime = 0;
    _drained = NO;
    _videoIndex = av_find_best_stream(_format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    _audioIndex = av_find_best_stream(_format, AVMEDIA_TYPE_AUDIO, -1, _videoIndex, NULL, 0);
    if (_videoIndex >= 0) {
        _video = [self openCodec:_videoIndex hardware:_hardwareDecoding error:error];
        if (!_video) { [self close]; return NO; }
    }
    if (_audioIndex >= 0) {
        _audio = [self openCodec:_audioIndex hardware:NO error:error];
        if (!_audio) { [self close]; return NO; }
    }
    if (!_video && !_audio) {
        [self close];
        return EDReaderError(error, @"No playable audio or video stream", AVERROR_STREAM_NOT_FOUND);
    }
    _packet = av_packet_alloc();
    _frame = av_frame_alloc();
    if (!_packet || !_frame) { [self close]; return EDReaderError(error, @"Allocate frame", AVERROR(ENOMEM)); }

    NSMutableArray *videos = [NSMutableArray array], *audios = [NSMutableArray array];
    // Put the selected/default stream first. Track IDs remain FFmpeg stream IDs.
    for (unsigned int i = 0; i < _format->nb_streams; i++) {
        AVStream *stream = _format->streams[i];
        AVCodecParameters *p = stream->codecpar;
        NSMutableDictionary *track = [@{@"index": @(i), @"codec": @(avcodec_get_name(p->codec_id))} mutableCopy];
        AVDictionaryEntry *language = av_dict_get(stream->metadata, "language", NULL, 0);
        AVDictionaryEntry *title = av_dict_get(stream->metadata, "title", NULL, 0);
        if (language) track[@"language"] = @(language->value);
        if (title) track[@"title"] = @(title->value);
        if (p->codec_type == AVMEDIA_TYPE_VIDEO && !(stream->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
            const AVPixFmtDescriptor *pixel = av_pix_fmt_desc_get(p->format);
            track[@"width"] = @(p->width);
            track[@"height"] = @(p->height);
            track[@"bitDepth"] = @(pixel ? pixel->comp[0].depth : 8);
            track[@"hardware"] = @(i == _videoIndex && _video->hw_device_ctx != NULL);
            if (i == _videoIndex) [videos insertObject:track atIndex:0]; else [videos addObject:track];
        } else if (p->codec_type == AVMEDIA_TYPE_AUDIO && avcodec_find_decoder(p->codec_id)) {
            track[@"channels"] = @(p->ch_layout.nb_channels);
            track[@"sampleRate"] = @(p->sample_rate);
            if (i == _audioIndex) [audios insertObject:track atIndex:0]; else [audios addObject:track];
        }
    }
    double fps = _video ? av_q2d(av_guess_frame_rate(_format, _format->streams[_videoIndex], NULL)) : 0;
    _frameDuration = fps > 0 && isfinite(fps) ? 1.0 / fps : 1.0 / 30;
    double duration = _format->duration == AV_NOPTS_VALUE ? 0 : (double)_format->duration / AV_TIME_BASE;
    _mediaInfo = @{@"duration": @(duration), @"video": videos, @"audio": audios,
        @"width": @(_video ? _video->width : 0), @"height": @(_video ? _video->height : 0),
        @"frameRate": @(isfinite(fps) ? fps : 0),
        @"hdr": @(_video && (_video->color_trc == AVCOL_TRC_SMPTE2084 || _video->color_trc == AVCOL_TRC_ARIB_STD_B67))};
    return YES;
}

- (EDFFmpegFrame *)videoFrameWithError:(NSError **)error {
    AVStream *stream = _format->streams[_videoIndex];
    double pts = _frame->best_effort_timestamp == AV_NOPTS_VALUE ? _videoNextTime :
        _frame->best_effort_timestamp * av_q2d(stream->time_base) - _origin;
    double duration = _frame->duration > 0 ? _frame->duration * av_q2d(stream->time_base) : _frameDuration;
    _videoNextTime = pts + duration;
    if (pts + 0.000001 < _seekFloor) return nil;
    CVPixelBufferRef pixel = edendale_frame_get_pixel_buffer(_frame);
    if (!pixel) pixel = edendale_create_pixel_buffer_from_sw_frame(_frame, &_scaler);
    if (!pixel) { EDReaderError(error, @"Convert video frame", AVERROR(EINVAL)); return nil; }
    EDFFmpegFrame *output = [[EDFFmpegFrame alloc] initWithPixelBuffer:pixel audio:NULL time:pts duration:duration];
    CVPixelBufferRelease(pixel);
    return output;
}

- (EDFFmpegFrame *)audioFrameWithError:(NSError **)error {
    if (_frame->sample_rate <= 0 || _frame->ch_layout.nb_channels <= 0) {
        EDReaderError(error, @"Invalid audio format", AVERROR(EINVAL)); return nil;
    }
    if (!_resampler || _inputRate != _frame->sample_rate || _inputFormat != _frame->format ||
        av_channel_layout_compare(&_inputLayout, &_frame->ch_layout) != 0) {
        swr_free(&_resampler);
        av_channel_layout_uninit(&_inputLayout);
        av_channel_layout_copy(&_inputLayout, &_frame->ch_layout);
        _inputRate = _frame->sample_rate;
        _inputFormat = _frame->format;
        AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        int result = swr_alloc_set_opts2(&_resampler, &stereo, AV_SAMPLE_FMT_FLT, 48000,
            &_inputLayout, _inputFormat, _inputRate, 0, NULL);
        if (result >= 0) result = swr_init(_resampler);
        if (result < 0) { EDReaderError(error, @"Convert audio format", result); return nil; }
    }
    int64_t delay = swr_get_delay(_resampler, _inputRate);
    int count = (int)av_rescale_rnd(delay + _frame->nb_samples, 48000, _inputRate, AV_ROUND_UP);
    NSMutableData *pcm = [NSMutableData dataWithLength:(NSUInteger)count * 2 * sizeof(float)];
    uint8_t *output[] = { pcm.mutableBytes };
    count = swr_convert(_resampler, output, count, (const uint8_t **)_frame->extended_data, _frame->nb_samples);
    if (count < 0) { EDReaderError(error, @"Decode audio samples", count); return nil; }
    AVStream *stream = _format->streams[_audioIndex];
    double pts = _frame->best_effort_timestamp == AV_NOPTS_VALUE ? _audioNextTime :
        _frame->best_effort_timestamp * av_q2d(stream->time_base) - _origin - (double)delay / _inputRate;
    _audioNextTime = pts + (double)count / 48000;
    int trim = (int)fmin(count, fmax(0, ceil((_seekFloor - pts) * 48000)));
    count -= trim;
    if (count == 0) return nil;
    pts += (double)trim / 48000;
    size_t size = (size_t)count * 2 * sizeof(float);
    CMBlockBufferRef block = NULL;
    CMAudioFormatDescriptionRef description = NULL;
    CMSampleBufferRef sample = NULL;
    AudioStreamBasicDescription asbd = { .mSampleRate = 48000, .mFormatID = kAudioFormatLinearPCM,
        .mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
        .mBytesPerPacket = 8, .mFramesPerPacket = 1, .mBytesPerFrame = 8, .mChannelsPerFrame = 2, .mBitsPerChannel = 32 };
    OSStatus status = CMBlockBufferCreateWithMemoryBlock(NULL, NULL, size, kCFAllocatorDefault, NULL, 0, size, 0, &block);
    if (!status) status = CMBlockBufferReplaceDataBytes((const uint8_t *)pcm.bytes + trim * 8, block, 0, size);
    if (!status) status = CMAudioFormatDescriptionCreate(NULL, &asbd, 0, NULL, 0, NULL, NULL, &description);
    CMSampleTimingInfo timing = { CMTimeMake(1, 48000), CMTimeMakeWithSeconds(pts, 48000), kCMTimeInvalid };
    size_t sampleSize = 8;
    if (!status) status = CMSampleBufferCreateReady(NULL, block, description, count, 1, &timing, 1, &sampleSize, &sample);
    EDFFmpegFrame *result = nil;
    if (!status) result = [[EDFFmpegFrame alloc] initWithPixelBuffer:NULL audio:sample time:pts duration:(double)count / 48000];
    else EDReaderError(error, @"Create audio sample buffer", status);
    if (sample) CFRelease(sample);
    if (description) CFRelease(description);
    if (block) CFRelease(block);
    return result;
}

- (BOOL)decode:(AVCodecContext *)codec packet:(AVPacket *)packet into:(NSMutableArray *)outputs error:(NSError **)error {
    int result = avcodec_send_packet(codec, packet);
    if (result < 0 && result != AVERROR_EOF) return EDReaderError(error, @"Submit media packet", result);
    while ((result = avcodec_receive_frame(codec, _frame)) >= 0) {
        NSError *conversionError = nil;
        EDFFmpegFrame *output = codec == _video ? [self videoFrameWithError:&conversionError] : [self audioFrameWithError:&conversionError];
        av_frame_unref(_frame);
        if (conversionError) { if (error) *error = conversionError; return NO; }
        if (output) [outputs addObject:output];
    }
    return result == AVERROR(EAGAIN) || result == AVERROR_EOF || EDReaderError(error, @"Decode media frame", result);
}

- (NSArray<EDFFmpegFrame *> *)readBatchWithError:(NSError **)error {
    if (!_format || _drained) return @[];
    NSMutableArray *outputs = [NSMutableArray array];
    // Yield regularly even when skipping subtitle/attachment packets.
    for (int i = 0; i < 64 && outputs.count == 0; i++) {
        if (atomic_load(&_interrupted)) { EDReaderError(error, @"Playback interrupted", AVERROR_EXIT); return nil; }
        atomic_store(&_deadline, (int64_t)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) + 20000000);
        int result = av_read_frame(_format, _packet);
        atomic_store(&_deadline, 0);
        if (result == AVERROR_EOF) {
            if (_video && ![self decode:_video packet:NULL into:outputs error:error]) return nil;
            if (_audio && ![self decode:_audio packet:NULL into:outputs error:error]) return nil;
            _drained = YES;
            return outputs;
        }
        if (result < 0) { EDReaderError(error, @"Read media packet", result); return nil; }
        AVCodecContext *codec = _packet->stream_index == _videoIndex ? _video :
            (_packet->stream_index == _audioIndex ? _audio : NULL);
        BOOL success = !codec || [self decode:codec packet:_packet into:outputs error:error];
        av_packet_unref(_packet);
        if (!success) return nil;
    }
    return outputs;
}

- (BOOL)seekToSeconds:(double)seconds error:(NSError **)error {
    if (!_format || !isfinite(seconds)) return EDReaderError(error, @"Seek unavailable", AVERROR(EINVAL));
    atomic_store(&_interrupted, false);
    atomic_store(&_deadline, (int64_t)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) + 20000000);
    int64_t timestamp = (int64_t)((fmax(0, seconds) + _origin) * AV_TIME_BASE);
    int result = avformat_seek_file(_format, -1, INT64_MIN, timestamp, timestamp, AVSEEK_FLAG_BACKWARD);
    atomic_store(&_deadline, 0);
    if (result < 0) return EDReaderError(error, @"Could not seek", result);
    if (_video) avcodec_flush_buffers(_video);
    if (_audio) avcodec_flush_buffers(_audio);
    swr_free(&_resampler);
    av_packet_unref(_packet);
    av_frame_unref(_frame);
    _seekFloor = _videoNextTime = _audioNextTime = fmax(0, seconds);
    _drained = NO;
    return YES;
}

- (BOOL)selectAudioTrack:(NSInteger)index error:(NSError **)error {
    if (!_format || index < 0 || index >= _format->nb_streams ||
        _format->streams[index]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO)
        return EDReaderError(error, @"Invalid audio track", AVERROR(EINVAL));
    AVCodecContext *next = [self openCodec:(int)index hardware:NO error:error];
    if (!next) return NO;
    avcodec_free_context(&_audio);
    _audio = next;
    _audioIndex = (int)index;
    swr_free(&_resampler);
    return YES;
}
@end

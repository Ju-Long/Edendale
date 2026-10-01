#import "FFmpegReader.h"
#import "FFmpegBridge.h"
#import "EDSMBFile.h"
#import <FFmpeg/FFmpeg.h>
#import <FFmpeg/libavutil/pixdesc.h>
#import <VideoToolbox/VideoToolbox.h>
#import <time.h>
#import <stdatomic.h>
#import <fcntl.h>
#import <unistd.h>
#import <sys/stat.h>

#pragma mark - Custom I/O for local files

static int ed_io_read(void *opaque, uint8_t *buf, int buf_size) {
    int fd = (int)(intptr_t)opaque;
    ssize_t n = read(fd, buf, buf_size);
    if (n == 0) return AVERROR_EOF;
    if (n < 0) return AVERROR(errno);
    return (int)n;
}

static int64_t ed_io_seek(void *opaque, int64_t offset, int whence) {
    int fd = (int)(intptr_t)opaque;
    if (whence == AVSEEK_SIZE) {
        struct stat st;
        if (fstat(fd, &st) < 0) return AVERROR(errno);
        return st.st_size;
    }
    off_t pos = lseek(fd, offset, whence);
    if (pos < 0) return AVERROR(errno);
    return pos;
}

#pragma mark - Custom I/O for byte sources (SMB, NFS, SFTP, HTTP providers)

typedef struct {
    // Unretained: the reader's `_byteSource` ivar owns the source for as
    // long as this context exists.
    void *source;
    int64_t position;
    _Atomic(bool) *interrupted;
} EDByteSourceContext;

static int ed_source_read(void *opaque, uint8_t *buf, int buf_size) {
    EDByteSourceContext *ctx = (EDByteSourceContext *)opaque;
    if (!ctx || !ctx->source) return AVERROR(EINVAL);
    _Atomic(bool) *interrupted = ctx->interrupted;
    if (atomic_load(interrupted)) return AVERROR_EXIT;
    id<EDByteSource> source = (__bridge id<EDByteSource>)ctx->source;
    NSInteger count = [source readAtOffset:ctx->position into:buf length:buf_size shouldAbort:^BOOL {
        return atomic_load(interrupted);
    }];
    if (count > 0) {
        ctx->position += count;
        return (int)count;
    }
    if (count == 0) return AVERROR_EOF;
    return atomic_load(interrupted) ? AVERROR_EXIT : AVERROR(EIO);
}

static int64_t ed_source_seek(void *opaque, int64_t offset, int whence) {
    EDByteSourceContext *ctx = (EDByteSourceContext *)opaque;
    if (!ctx || !ctx->source) return AVERROR(EINVAL);
    int64_t length = ((__bridge id<EDByteSource>)ctx->source).length;
    int64_t target;
    switch (whence & ~AVSEEK_FORCE) {
        case AVSEEK_SIZE: return length >= 0 ? length : AVERROR(ENOSYS);
        case SEEK_SET: target = offset; break;
        case SEEK_CUR: target = ctx->position + offset; break;
        case SEEK_END:
            if (length < 0) return AVERROR(ENOSYS);
            target = length + offset;
            break;
        default: return AVERROR(EINVAL);
    }
    if (target < 0) return AVERROR(EINVAL);
    // Seeking only moves the position; the source fetches on the next read.
    ctx->position = target;
    return target;
}

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
- (instancetype)initWithSubtitle:(NSDictionary *)subtitle time:(double)time duration:(double)duration {
    if ((self = [self initWithPixelBuffer:NULL audio:NULL time:time duration:duration])) {
        _subtitle = [subtitle copy];
    }
    return self;
}
- (void)dealloc {
    if (_pixelBuffer) CVPixelBufferRelease(_pixelBuffer);
    if (_audioSampleBuffer) CFRelease(_audioSampleBuffer);
}
@end

/// A demuxed video packet waiting, still compressed, for room to decode it.
@interface EDHeldPacket : NSObject {
@public
    AVPacket *_packet;
}
@end

@implementation EDHeldPacket
- (void)dealloc { av_packet_free(&_packet); }
@end

/// Compressed video held while the player reads on for audio alone. Holding
/// stops here: about 2.5 s of 100 Mbit/s video, far more at typical bit rates.
static const size_t EDHeldVideoLimit = 32 << 20;

@implementation EDFFmpegReader {
    AVFormatContext *_format;
    AVIOContext *_customIO;
    int _fileFD;
    id<EDByteSource> _byteSource;
    EDByteSourceContext *_byteSourceContext;
    AVCodecContext *_video;
    AVCodecContext *_audio;
    AVCodecContext *_subtitle;
    int _subtitleIndex;
    AVPacket *_packet;
    AVFrame *_frame;
    struct SwsContext *_scaler;
    CVPixelBufferPoolRef _pixelBufferPool;
    SwrContext *_resampler;
    AVChannelLayout _inputLayout;
    int _inputRate;
    enum AVSampleFormat _inputFormat;
    int _videoIndex;
    int _audioIndex;
    BOOL _hardwareDecoding;
    BOOL _drained;
    BOOL _demuxEnded;
    // Video packets read while the player needed only audio, in demux order,
    // so the audio interleaved behind them could be decoded first.
    NSMutableArray<EDHeldPacket *> *_heldVideo;
    size_t _heldVideoBytes;
    double _origin;
    double _seekFloor;
    double _videoNextTime;
    double _audioNextTime;
    double _frameDuration;
    atomic_bool _interrupted;
    atomic_int_fast64_t _deadline;
    // libavcodec's av1 decoder only drives hwaccels and FFmpeg 7.1 has no
    // VideoToolbox one, so AV1 packets go to VideoToolbox directly.
    CMVideoFormatDescriptionRef _av1Format;
    VTDecompressionSessionRef _av1Session;
    BOOL _av1NeedsKeyframe;
    // A replaced video decoder has no reference frames, so packets wait for
    // the next keyframe.
    BOOL _videoNeedsKeyframe;
    // Decoder replacements since the last decoded video frame.
    int _videoRecoveries;
}

static BOOL EDReaderError(NSError **error, NSString *operation, int code) {
    if (error) {
        *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:code userInfo:@{
            NSLocalizedDescriptionKey: [NSString stringWithFormat:@"%@: %@", operation, edendale_av_err2str(code)]
        }];
    }
    return NO;
}

/// Like EDReaderError, but prefers the byte source's own explanation (for
/// example "Sign in to Google Drive again") over FFmpeg's generic I/O error.
static BOOL EDSourceError(NSError **error, id<EDByteSource> source, NSString *operation, int code) {
    NSString *reason = code == AVERROR_EXIT ? nil : source.failureReason;
    if (reason.length == 0) return EDReaderError(error, operation, code);
    if (error) {
        *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:code userInfo:@{
            NSLocalizedDescriptionKey: reason
        }];
    }
    return NO;
}

static BOOL EDVideoToolboxError(NSError **error, NSString *operation, OSStatus status) {
    if (error) {
        *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:status userInfo:@{
            NSLocalizedDescriptionKey: [NSString stringWithFormat:@"%@ (VideoToolbox error %d)", operation, (int)status]
        }];
    }
    return NO;
}

/// Matroska, WebM, and MP4 store the av1C record (marker bit, version 1) as
/// extradata; VideoToolbox needs it to configure the decoder.
static BOOL EDHasAV1Configuration(const AVCodecParameters *parameters) {
    return parameters->extradata_size >= 4 && parameters->extradata[0] == 0x81;
}

static int EDAV1BitDepth(const AVCodecParameters *parameters) {
    if (!EDHasAV1Configuration(parameters) || !(parameters->extradata[2] & 0x40)) return 8;
    return (parameters->extradata[2] & 0x20) ? 12 : 10;
}

static int EDInterrupt(void *opaque) {
    EDFFmpegReader *reader = (__bridge EDFFmpegReader *)opaque;
    return atomic_load(&reader->_interrupted) ||
        (atomic_load(&reader->_deadline) > 0 && (int64_t)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) > atomic_load(&reader->_deadline));
}

- (instancetype)initWithHardwareDecoding:(BOOL)hardwareDecoding {
    if ((self = [super init])) {
        _hardwareDecoding = hardwareDecoding;
        _videoDecodingEnabled = YES;
        _fileFD = -1;
        _videoIndex = _audioIndex = _subtitleIndex = -1;
        _mediaInfo = @{};
        _heldVideo = [NSMutableArray array];
        atomic_init(&_interrupted, false);
        atomic_init(&_deadline, 0);
    }
    return self;
}

- (void)interrupt { atomic_store(&_interrupted, true); }
- (BOOL)atEnd { return _drained; }

- (void)close {
    [self discardHeldVideo];
    _videoNeedsKeyframe = NO;
    _videoRecoveries = 0;
    avcodec_free_context(&_video);
    avcodec_free_context(&_audio);
    avcodec_free_context(&_subtitle);
    [self invalidateAV1Session];
    if (_av1Format) {
        CFRelease(_av1Format);
        _av1Format = NULL;
    }
    avformat_close_input(&_format);
    if (_customIO) {
        av_freep(&_customIO->buffer);
        avio_context_free(&_customIO);
    }
    if (_fileFD >= 0) {
        close(_fileFD);
        _fileFD = -1;
    }
    [_byteSource cancel];
    _byteSource = nil;
    free(_byteSourceContext);
    _byteSourceContext = NULL;
    av_packet_free(&_packet);
    av_frame_free(&_frame);
    sws_freeContext(_scaler);
    _scaler = NULL;
    if (_pixelBufferPool) {
        CVPixelBufferPoolRelease(_pixelBufferPool);
        _pixelBufferPool = NULL;
    }
    swr_free(&_resampler);
    av_channel_layout_uninit(&_inputLayout);
    _videoIndex = _audioIndex = _subtitleIndex = -1;
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

- (BOOL)recreateVideoDecoder {
    if (_videoIndex < 0 || !_format) return NO;
    if (_av1Format) {
        [self invalidateAV1Session];
        return [self openAV1SessionWithError:nil];
    }
    _videoRecoveries = 0;
    return [self replaceVideoDecoderUsingHardware:_hardwareDecoding];
}

- (BOOL)replaceVideoDecoderUsingHardware:(BOOL)hardware {
    avcodec_free_context(&_video);
    NSError *err = nil;
    _video = [self openCodec:_videoIndex hardware:hardware error:&err];
    if (!_video && hardware) {
        NSLog(@"[FFmpegReader] Hardware video decoder creation failed; falling back to software: %@", err);
        _video = [self openCodec:_videoIndex hardware:NO error:&err];
    }
    _videoNeedsKeyframe = YES;
    if (_video) {
        NSLog(@"[FFmpegReader] Recreated video decoder successfully (hardware=%d)", _video->hw_device_ctx != NULL);
        return YES;
    }
    return NO;
}

#pragma mark - AV1 (VideoToolbox)

/// VideoToolbox decodes AV1 only in hardware (M3, A17 Pro, or later; never in
/// simulators). `hardwareDecoding` is not consulted: AV1 has no other path.
- (BOOL)openAV1SessionWithError:(NSError **)error {
    AVCodecParameters *parameters = _format->streams[_videoIndex]->codecpar;
    BOOL fullRange = parameters->color_range == AVCOL_RANGE_JPEG;
    if (!_av1Format) {
        if (!EDHasAV1Configuration(parameters)) {
            return EDReaderError(error, @"AV1 video has no decoder configuration", AVERROR_INVALIDDATA);
        }
        NSData *configuration = [NSData dataWithBytes:parameters->extradata length:(NSUInteger)parameters->extradata_size];
        NSDictionary *extensions = @{
            (__bridge NSString *)kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms: @{@"av1C": configuration},
            (__bridge NSString *)kCMFormatDescriptionExtension_FullRangeVideo: @(fullRange)
        };
        OSStatus status = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, kCMVideoCodecType_AV1,
            parameters->width, parameters->height, (__bridge CFDictionaryRef)extensions, &_av1Format);
        if (status != noErr) return EDVideoToolboxError(error, @"Describe AV1 video", status);
    }
    OSType pixelFormat = EDAV1BitDepth(parameters) > 8
        ? (fullRange ? kCVPixelFormatType_420YpCbCr10BiPlanarFullRange : kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange)
        : (fullRange ? kCVPixelFormatType_420YpCbCr8BiPlanarFullRange : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange);
    NSDictionary *attributes = @{
        (__bridge NSString *)kCVPixelBufferPixelFormatTypeKey: @(pixelFormat),
        (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{},
        (__bridge NSString *)kCVPixelBufferMetalCompatibilityKey: @YES
    };
    OSStatus status = VTDecompressionSessionCreate(kCFAllocatorDefault, _av1Format, NULL,
        (__bridge CFDictionaryRef)attributes, NULL, &_av1Session);
    if (status != noErr) {
        _av1Session = NULL;
        return EDVideoToolboxError(error, @"This device cannot decode AV1 video", status);
    }
    _av1NeedsKeyframe = YES;
    return YES;
}

- (void)invalidateAV1Session {
    if (!_av1Session) return;
    VTDecompressionSessionInvalidate(_av1Session);
    CFRelease(_av1Session);
    _av1Session = NULL;
}

/// Decodes one temporal unit. With both decode flags clear, VideoToolbox calls
/// the handler before returning. Undecodable units are dropped, never fatal.
- (void)decodeAV1Packet:(AVPacket *)packet into:(NSMutableArray *)outputs {
    // A seek or a new session leaves no reference frames.
    if (_av1NeedsKeyframe && !(packet->flags & AV_PKT_FLAG_KEY)) return;
    if (!_av1Session && ![self openAV1SessionWithError:nil]) return;
    AVStream *stream = _format->streams[_videoIndex];
    double pts = packet->pts == AV_NOPTS_VALUE ? _videoNextTime :
        packet->pts * av_q2d(stream->time_base) - _origin;
    double duration = packet->duration > 0 ? packet->duration * av_q2d(stream->time_base) : _frameDuration;
    _videoNextTime = pts + duration;

    size_t size = (size_t)packet->size;
    CMBlockBufferRef block = NULL;
    CMSampleBufferRef sample = NULL;
    OSStatus status = CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, NULL, size, kCFAllocatorDefault,
        NULL, 0, size, kCMBlockBufferAssureMemoryNowFlag, &block);
    if (status == noErr) status = CMBlockBufferReplaceDataBytes(packet->data, block, 0, size);
    CMSampleTimingInfo timing = { CMTimeMakeWithSeconds(duration, 90000), CMTimeMakeWithSeconds(pts, 90000), kCMTimeInvalid };
    if (status == noErr) status = CMSampleBufferCreateReady(kCFAllocatorDefault, block, _av1Format, 1, 1, &timing, 1, &size, &sample);
    if (block) CFRelease(block);
    if (status != noErr) return;

    __block CVPixelBufferRef decoded = NULL;
    status = VTDecompressionSessionDecodeFrameWithOutputHandler(_av1Session, sample, 0, NULL,
        ^(OSStatus result, VTDecodeInfoFlags flags, CVImageBufferRef image, CMTime time, CMTime length) {
            if (result == noErr && image && !(flags & kVTDecodeInfo_FrameDropped)) decoded = CVPixelBufferRetain(image);
        });
    CFRelease(sample);
    if (status == kVTInvalidSessionErr) {
        // iOS invalidates hardware sessions in the background.
        [self invalidateAV1Session];
        _av1NeedsKeyframe = YES;
    }
    if (!decoded) return;
    _av1NeedsKeyframe = NO;
    if (pts + 0.000001 >= _seekFloor) {
        [outputs addObject:[[EDFFmpegFrame alloc] initWithPixelBuffer:decoded audio:NULL time:pts duration:duration]];
    }
    CVPixelBufferRelease(decoded);
}

/// Closes any open media and allocates a fresh format context with the
/// interrupt callback and the open deadline armed.
- (BOOL)prepareToOpenWithError:(NSError **)error {
    [self close];
    if (atomic_load(&_interrupted)) return EDReaderError(error, @"Playback cancelled", AVERROR_EXIT);
    if (!edendale_ffmpeg_versions_match()) {
        return EDReaderError(error, @"FFmpeg library versions do not match; rebuild the FFmpeg framework", AVERROR(EINVAL));
    }
    static dispatch_once_t once;
    dispatch_once(&once, ^{ avformat_network_init(); });
    _format = avformat_alloc_context();
    if (!_format) return EDReaderError(error, @"Allocate media reader", AVERROR(ENOMEM));
    _format->interrupt_callback = (AVIOInterruptCB){ EDInterrupt, (__bridge void *)self };
    atomic_store(&_deadline, (int64_t)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) + 20000000);
    return YES;
}

- (BOOL)openByteSource:(id<EDByteSource>)source name:(NSString *)name error:(NSError **)error {
    if (![self prepareToOpenWithError:error]) {
        [source cancel];
        return NO;
    }
    _byteSource = source;
    _byteSourceContext = (EDByteSourceContext *)calloc(1, sizeof(EDByteSourceContext));
    if (!_byteSourceContext) {
        atomic_store(&_deadline, 0);
        [self close];
        return EDReaderError(error, @"Allocate I/O context", AVERROR(ENOMEM));
    }
    _byteSourceContext->source = (__bridge void *)source;
    _byteSourceContext->interrupted = &_interrupted;

    // FFmpeg asks the source for up to 64 KiB at a time, which the sources
    // serve from the larger chunks they fetch ahead.
    static const int kSourceIOBufSize = 65536;
    unsigned char *ioBuf = av_malloc(kSourceIOBufSize);
    if (!ioBuf) {
        atomic_store(&_deadline, 0);
        [self close];
        return EDReaderError(error, @"Allocate I/O buffer", AVERROR(ENOMEM));
    }
    _customIO = avio_alloc_context(ioBuf, kSourceIOBufSize, 0, _byteSourceContext,
                                   ed_source_read, NULL, ed_source_seek);
    if (!_customIO) {
        av_free(ioBuf);
        atomic_store(&_deadline, 0);
        [self close];
        return EDReaderError(error, @"Allocate I/O context", AVERROR(ENOMEM));
    }
    _format->pb = _customIO;
    _format->flags |= AVFMT_FLAG_CUSTOM_IO;
    return [self finishOpeningAt:(name.length > 0 ? name.UTF8String : "media") error:error];
}

- (BOOL)openURL:(NSURL *)url error:(NSError **)error {
    NSString *scheme = url.scheme.lowercaseString;
    if ([scheme isEqualToString:@"smb"] || [scheme isEqualToString:@"smb2"]) {
        EDBufferedByteSource *source = [EDSMBFile byteSourceForURL:url error:error];
        if (!source) {
            [self close];
            return NO;
        }
        return [self openByteSource:source name:url.lastPathComponent error:error];
    }
    if (![self prepareToOpenWithError:error]) return NO;

    const char *location;
    if (url.isFileURL) {
        location = url.fileSystemRepresentation;
        _fileFD = open(location, O_RDONLY);
        if (_fileFD < 0) {
            int err = errno;
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Could not open file", AVERROR(err));
        }
        static const int kIOBufSize = 32768;
        unsigned char *ioBuf = av_malloc(kIOBufSize);
        if (!ioBuf) {
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Allocate I/O buffer", AVERROR(ENOMEM));
        }
        _customIO = avio_alloc_context(ioBuf, kIOBufSize, 0,
                                       (void *)(intptr_t)_fileFD,
                                       ed_io_read, NULL, ed_io_seek);
        if (!_customIO) {
            av_free(ioBuf);
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Allocate I/O context", AVERROR(ENOMEM));
        }
        _format->pb = _customIO;
        _format->flags |= AVFMT_FLAG_CUSTOM_IO;
    } else {
        // Remote schemes stream through -openByteSource:. Handing any other
        // URL to FFmpeg's own protocols would send it, and whatever it
        // carries, over connections that don't verify certificates.
        atomic_store(&_deadline, 0);
        [self close];
        if (error) {
            *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:AVERROR_PROTOCOL_NOT_FOUND userInfo:@{
                NSLocalizedDescriptionKey: @"Edendale can't open this kind of location."
            }];
        }
        return NO;
    }
    return [self finishOpeningAt:location error:error];
}

/// Probes the media behind the prepared I/O and sets up its decoders.
- (BOOL)finishOpeningAt:(const char *)location error:(NSError **)error {
    int result = avformat_open_input(&_format, location, NULL, NULL);
    if (result >= 0) result = avformat_find_stream_info(_format, NULL);
    atomic_store(&_deadline, 0);
    if (result < 0) {
        id<EDByteSource> source = _byteSource;
        [self close];
        return EDSourceError(error, source, @"Could not open media", result);
    }

    _origin = _format->start_time == AV_NOPTS_VALUE ? 0 : (double)_format->start_time / AV_TIME_BASE;
    _seekFloor = _videoNextTime = _audioNextTime = 0;
    _drained = _demuxEnded = NO;
    _videoIndex = av_find_best_stream(_format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    _audioIndex = av_find_best_stream(_format, AVMEDIA_TYPE_AUDIO, -1, _videoIndex, NULL, 0);
    AVCodecParameters *videoParameters = _videoIndex >= 0 ? _format->streams[_videoIndex]->codecpar : NULL;
    if (videoParameters && videoParameters->codec_id == AV_CODEC_ID_AV1) {
        if (![self openAV1SessionWithError:error]) { [self close]; return NO; }
    } else if (videoParameters) {
        _video = [self openCodec:_videoIndex hardware:_hardwareDecoding error:error];
        if (!_video) { [self close]; return NO; }
    }
    if (_audioIndex >= 0) {
        _audio = [self openCodec:_audioIndex hardware:NO error:error];
        if (!_audio) { [self close]; return NO; }
    }
    if (!videoParameters && !_audio) {
        [self close];
        return EDReaderError(error, @"No playable audio or video stream", AVERROR_STREAM_NOT_FOUND);
    }
    _packet = av_packet_alloc();
    _frame = av_frame_alloc();
    if (!_packet || !_frame) { [self close]; return EDReaderError(error, @"Allocate frame", AVERROR(ENOMEM)); }

    NSMutableArray *videos = [NSMutableArray array], *audios = [NSMutableArray array], *subtitles = [NSMutableArray array];
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
            track[@"bitDepth"] = @(p->codec_id == AV_CODEC_ID_AV1 ? EDAV1BitDepth(p) : pixel ? pixel->comp[0].depth : 8);
            BOOL hardware = _av1Session ? VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1) :
                _video && _video->hw_device_ctx != NULL;
            track[@"hardware"] = @(i == _videoIndex && hardware);
            if (i == _videoIndex) [videos insertObject:track atIndex:0]; else [videos addObject:track];
        } else if (p->codec_type == AVMEDIA_TYPE_AUDIO && avcodec_find_decoder(p->codec_id)) {
            track[@"channels"] = @(p->ch_layout.nb_channels);
            track[@"sampleRate"] = @(p->sample_rate);
            if (i == _audioIndex) [audios insertObject:track atIndex:0]; else [audios addObject:track];
        } else if (p->codec_type == AVMEDIA_TYPE_SUBTITLE) {
            BOOL isImage = (p->codec_id == AV_CODEC_ID_HDMV_PGS_SUBTITLE ||
                            p->codec_id == AV_CODEC_ID_DVD_SUBTITLE);
            track[@"isImageBased"] = @(isImage);
            [subtitles addObject:track];
        }
    }
    double fps = videoParameters ? av_q2d(av_guess_frame_rate(_format, _format->streams[_videoIndex], NULL)) : 0;
    _frameDuration = fps > 0 && isfinite(fps) ? 1.0 / fps : 1.0 / 30;
    double duration = _format->duration == AV_NOPTS_VALUE ? 0 : (double)_format->duration / AV_TIME_BASE;
    enum AVColorTransferCharacteristic transfer = _video ? _video->color_trc :
        videoParameters ? videoParameters->color_trc : AVCOL_TRC_UNSPECIFIED;
    _mediaInfo = @{@"duration": @(duration), @"video": videos, @"audio": audios, @"subtitle": subtitles,
        @"width": @(_video ? _video->width : videoParameters ? videoParameters->width : 0),
        @"height": @(_video ? _video->height : videoParameters ? videoParameters->height : 0),
        @"frameRate": @(isfinite(fps) ? fps : 0),
        @"hdr": @(transfer == AVCOL_TRC_SMPTE2084 || transfer == AVCOL_TRC_ARIB_STD_B67)};
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
    if (!pixel) pixel = edendale_create_pixel_buffer_from_sw_frame(_frame, &_scaler, &_pixelBufferPool);
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
    double pts;
    if (_frame->best_effort_timestamp == AV_NOPTS_VALUE) {
        pts = _audioNextTime;
    } else {
        double streamPts = _frame->best_effort_timestamp * av_q2d(stream->time_base) - _origin - (double)delay / _inputRate;
        if (fabs(streamPts - _audioNextTime) < 0.05) {
            pts = _audioNextTime;
        } else {
            pts = streamPts;
        }
    }
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

/// Replaces a hardware video decoder after `packet` failed, most often a
/// VideoToolbox session that iOS invalidated in the background. Returns the
/// decoder that took the packet, or NULL when it was dropped. The replacement
/// starts at a keyframe; if it fails again before decoding a frame, the
/// hardware can't decode this stream and a software decoder takes over.
- (AVCodecContext *)recoverVideoDecoderAfterError:(int)result packet:(AVPacket *)packet {
    if (!_video->hw_device_ctx) {
        // Software decoders skip damaged data; a new one would not help.
        NSLog(@"[FFmpegReader] Dropping undecodable video packet (err=%d)", result);
        return NULL;
    }
    BOOL hardware = _videoRecoveries == 0;
    _videoRecoveries += 1;
    NSLog(@"[FFmpegReader] Video decoding failed (err=%d); replacing the decoder (hardware=%d)", result, hardware);
    if (![self replaceVideoDecoderUsingHardware:hardware]) return NULL;
    if (!packet || !(packet->flags & AV_PKT_FLAG_KEY)) return NULL;
    _videoNeedsKeyframe = NO;
    result = avcodec_send_packet(_video, packet);
    if (result < 0 && result != AVERROR_EOF) {
        NSLog(@"[FFmpegReader] Dropping video packet the new decoder rejected (err=%d)", result);
        return NULL;
    }
    return _video;
}

- (BOOL)decode:(AVCodecContext *)codec packet:(AVPacket *)packet into:(NSMutableArray *)outputs error:(NSError **)error {
    BOOL isVideo = codec == _video;
    int result = avcodec_send_packet(codec, packet);
    if (result < 0 && result != AVERROR_EOF) {
        if (!isVideo) return EDReaderError(error, @"Submit media packet", result);
        // A video packet never fails the batch, which would stop the audio and
        // end playback. Recovery frees the decoder `codec` points to.
        codec = [self recoverVideoDecoderAfterError:result packet:packet];
        if (!codec) return YES;
    }
    while ((result = avcodec_receive_frame(codec, _frame)) >= 0) {
        if (isVideo) _videoRecoveries = 0;
        NSError *conversionError = nil;
        EDFFmpegFrame *output = isVideo ? [self videoFrameWithError:&conversionError] : [self audioFrameWithError:&conversionError];
        av_frame_unref(_frame);
        if (conversionError) {
            if (isVideo) {
                NSLog(@"[FFmpegReader] Failed to convert video frame (%@); skipping frame", conversionError);
                continue;
            }
            if (error) *error = conversionError;
            return NO;
        }
        if (output) [outputs addObject:output];
    }
    if (isVideo) {
        return YES;
    }
    return result == AVERROR(EAGAIN) || result == AVERROR_EOF || EDReaderError(error, @"Decode media frame", result);
}

/// Whether the frame in a video packet falls before the seek target, where
/// `videoFrameWithError:` would drop it.
- (BOOL)packetPrecedesSeekFloor:(const AVPacket *)packet {
    if (packet->pts == AV_NOPTS_VALUE) return NO;
    double pts = packet->pts * av_q2d(_format->streams[packet->stream_index]->time_base) - _origin;
    return pts + 0.000001 < _seekFloor;
}

/// Decodes one video packet, read just now or held back earlier.
- (BOOL)decodeVideoPacket:(AVPacket *)packet into:(NSMutableArray *)outputs error:(NSError **)error {
    if (_av1Format) {
        [self decodeAV1Packet:packet into:outputs];
        return YES;
    }
    if (!_video) return YES;
    if (_videoNeedsKeyframe) {
        if (!(packet->flags & AV_PKT_FLAG_KEY)) return YES;
        _videoNeedsKeyframe = NO;
    }
    // Frames before a seek target only rebuild the references of the
    // frames after it and are then dropped, so skip the ones no other
    // frame refers to (as mpv's precise seeks do). Hardware decoding
    // runs one frame at a time, so this shortens the post-seek freeze.
    _video->skip_frame = [self packetPrecedesSeekFloor:packet] ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
    return [self decode:_video packet:packet into:outputs error:error];
}

- (void)holdVideoPacket:(AVPacket *)packet {
    EDHeldPacket *held = [EDHeldPacket new];
    held->_packet = av_packet_alloc();
    if (!held->_packet) return;
    av_packet_move_ref(held->_packet, packet);
    _heldVideoBytes += (size_t)held->_packet->size;
    [_heldVideo addObject:held];
}

- (void)discardHeldVideo {
    [_heldVideo removeAllObjects];
    _heldVideoBytes = 0;
}

- (NSArray<EDFFmpegFrame *> *)readBatchWithError:(NSError **)error {
    return [self readBatchDecodingVideo:YES error:error];
}

- (NSArray<EDFFmpegFrame *> *)readBatchDecodingVideo:(BOOL)decodeVideo error:(NSError **)error {
    _blockedOnVideo = NO;
    if (!_format || _drained) return @[];
    if (!_videoDecodingEnabled) [self discardHeldVideo];
    NSMutableArray *outputs = [NSMutableArray array];
    // Yield regularly even when skipping subtitle/attachment packets.
    for (int i = 0; i < 64 && outputs.count == 0; i++) {
        if (atomic_load(&_interrupted)) { EDReaderError(error, @"Playback interrupted", AVERROR_EXIT); return nil; }
        if (_heldVideo.count > 0) {
            if (decodeVideo) {
                EDHeldPacket *held = _heldVideo.firstObject;
                [_heldVideo removeObjectAtIndex:0];
                _heldVideoBytes -= (size_t)held->_packet->size;
                if (![self decodeVideoPacket:held->_packet into:outputs error:error]) return nil;
                continue;
            }
            if (_demuxEnded || _heldVideoBytes >= EDHeldVideoLimit) {
                _blockedOnVideo = YES;
                return outputs;
            }
        }
        if (_demuxEnded) {
            // Draining an already drained decoder is harmless and returns nothing.
            if (_audio && ![self decode:_audio packet:NULL into:outputs error:error]) return nil;
            if (_video && _videoDecodingEnabled) {
                if (!decodeVideo) {
                    _blockedOnVideo = YES;
                    return outputs;
                }
                if (![self decode:_video packet:NULL into:outputs error:error]) return nil;
            }
            _drained = YES;
            return outputs;
        }
        atomic_store(&_deadline, (int64_t)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000) + 20000000);
        int result = av_read_frame(_format, _packet);
        atomic_store(&_deadline, 0);
        // Demuxers can report a failed network read as the end of the file;
        // the I/O context still holds the real error.
        if (result == AVERROR_EOF && _byteSource && _format->pb && _format->pb->error < 0 &&
            _format->pb->error != AVERROR_EOF && !atomic_load(&_interrupted)) {
            result = _format->pb->error;
        }
        if (result == AVERROR_EOF) {
            _demuxEnded = YES;
            continue;
        }
        if (result < 0) { EDSourceError(error, _byteSource, @"Read media packet", result); return nil; }
        if (_subtitle && _packet->stream_index == _subtitleIndex) {
            [self decodeSubtitle:_packet into:outputs];
            av_packet_unref(_packet);
            continue;
        }
        BOOL success = YES;
        if (_packet->stream_index == _videoIndex) {
            if (_videoDecodingEnabled && decodeVideo) {
                success = [self decodeVideoPacket:_packet into:outputs error:error];
            } else if (_videoDecodingEnabled) {
                [self holdVideoPacket:_packet];
            }
        } else if (_audio && _packet->stream_index == _audioIndex) {
            success = [self decode:_audio packet:_packet into:outputs error:error];
        }
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
    if (result < 0) return EDSourceError(error, _byteSource, @"Could not seek", result);
    // A read the seek interrupted left its error behind; reads resume now.
    if (_byteSource && _format->pb) _format->pb->error = 0;
    if (_video) avcodec_flush_buffers(_video);
    if (_audio) avcodec_flush_buffers(_audio);
    if (_subtitle) avcodec_flush_buffers(_subtitle);
    _av1NeedsKeyframe = YES;
    swr_free(&_resampler);
    av_packet_unref(_packet);
    av_frame_unref(_frame);
    [self discardHeldVideo];
    _seekFloor = _videoNextTime = _audioNextTime = fmax(0, seconds);
    _drained = _demuxEnded = NO;
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

- (NSDictionary<NSString *, id> *)selectSubtitleTrack:(NSInteger)index error:(NSError **)error {
    if (index < 0) {
        avcodec_free_context(&_subtitle);
        _subtitleIndex = -1;
        return @{};
    }
    if (!_format || index >= _format->nb_streams ||
        _format->streams[index]->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) {
        EDReaderError(error, @"Invalid subtitle track", AVERROR(EINVAL));
        return nil;
    }
    AVCodecContext *next = [self openCodec:(int)index hardware:NO error:error];
    if (!next) return nil;
    avcodec_free_context(&_subtitle);
    _subtitle = next;
    _subtitleIndex = (int)index;
    enum AVCodecID codec = next->codec_id;
    NSString *format = codec == AV_CODEC_ID_HDMV_PGS_SUBTITLE ? @"pgs" :
        codec == AV_CODEC_ID_DVD_SUBTITLE ? @"vobsub" : @"ass";
    NSData *header = next->subtitle_header_size > 0
        ? [NSData dataWithBytes:next->subtitle_header length:next->subtitle_header_size] : [NSData data];
    return @{@"format": format, @"header": header};
}

- (void)decodeSubtitle:(AVPacket *)packet into:(NSMutableArray *)outputs {
    AVSubtitle subtitle = {0};
    int gotSubtitle = 0;
    int result = avcodec_decode_subtitle2(_subtitle, &subtitle, &gotSubtitle, packet);
    if (result < 0 || !gotSubtitle) {
        avsubtitle_free(&subtitle);
        return;
    }
    AVStream *stream = _format->streams[_subtitleIndex];
    double base = subtitle.pts != AV_NOPTS_VALUE ? (double)subtitle.pts / AV_TIME_BASE :
        packet->pts != AV_NOPTS_VALUE ? packet->pts * av_q2d(stream->time_base) : _seekFloor + _origin;
    double start = base - _origin + subtitle.start_display_time / 1000.0;
    double duration = subtitle.end_display_time > subtitle.start_display_time && subtitle.end_display_time != UINT32_MAX
        ? (subtitle.end_display_time - subtitle.start_display_time) / 1000.0
        : packet->duration > 0 ? packet->duration * av_q2d(stream->time_base) : 4.0;
    NSMutableArray *texts = [NSMutableArray array];
    NSMutableArray *rects = [NSMutableArray array];
    for (unsigned i = 0; i < subtitle.num_rects; i++) {
        AVSubtitleRect *rect = subtitle.rects[i];
        if (rect->ass || rect->text) {
            NSString *text = [NSString stringWithUTF8String:rect->ass ?: rect->text];
            if (text) [texts addObject:text];
        } else if (rect->type == SUBTITLE_BITMAP && rect->data[0] && rect->data[1] &&
                   rect->w > 0 && rect->h > 0 && rect->w <= 8192 && rect->h <= 8192) {
            NSMutableData *rgba = [NSMutableData dataWithLength:(NSUInteger)rect->w * rect->h * 4];
            uint8_t *pixels = rgba.mutableBytes;
            const uint32_t *palette = (const uint32_t *)rect->data[1];
            for (int y = 0; y < rect->h; y++) {
                for (int x = 0; x < rect->w; x++) {
                    unsigned index = rect->data[0][y * rect->linesize[0] + x];
                    uint32_t color = index < rect->nb_colors ? palette[index] : 0;
                    NSUInteger offset = ((NSUInteger)y * rect->w + x) * 4;
                    pixels[offset] = (color >> 16) & 255;
                    pixels[offset + 1] = (color >> 8) & 255;
                    pixels[offset + 2] = color & 255;
                    pixels[offset + 3] = (color >> 24) & 255;
                }
            }
            [rects addObject:@{@"x": @(rect->x), @"y": @(rect->y), @"width": @(rect->w),
                @"height": @(rect->h), @"data": rgba}];
        }
    }
    if (start + duration >= _seekFloor) {
        NSDictionary *cue = @{@"texts": texts, @"rects": rects, @"trackIndex": @(_subtitleIndex),
            @"width": @(_subtitle->width), @"height": @(_subtitle->height)};
        [outputs addObject:[[EDFFmpegFrame alloc] initWithSubtitle:cue time:start duration:duration]];
    }
    avsubtitle_free(&subtitle);
}
@end

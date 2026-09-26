#import "FFmpegReader.h"
#import "FFmpegBridge.h"
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

#pragma mark - Custom I/O for SMB network shares (libsmb2)

struct smb2_context;
struct smb2fh;

struct smb2_stat_64 {
    uint32_t smb2_type;
    uint32_t smb2_nlink;
    uint64_t smb2_ino;
    uint64_t smb2_size;
    uint64_t smb2_atime;
    uint64_t smb2_atime_nsec;
    uint64_t smb2_mtime;
    uint64_t smb2_mtime_nsec;
    uint64_t smb2_ctime;
    uint64_t smb2_ctime_nsec;
    uint64_t smb2_btime;
    uint64_t smb2_btime_nsec;
    uint32_t smb2_attributes;
    uint32_t smb2_reparse_tag;
};

extern struct smb2_context *smb2_init_context(void);
extern void smb2_destroy_context(struct smb2_context *smb2);
extern void smb2_set_user(struct smb2_context *smb2, const char *user);
extern void smb2_set_password(struct smb2_context *smb2, const char *password);
extern void smb2_set_domain(struct smb2_context *smb2, const char *domain);
extern void smb2_set_timeout(struct smb2_context *smb2, int seconds);
extern int smb2_connect_share(struct smb2_context *smb2, const char *server, const char *share, const char *user);
extern int smb2_disconnect_share(struct smb2_context *smb2);
extern struct smb2fh *smb2_open(struct smb2_context *smb2, const char *path, int flags);
extern int smb2_close(struct smb2_context *smb2, struct smb2fh *fh);
extern int smb2_read(struct smb2_context *smb2, struct smb2fh *fh, uint8_t *buf, uint32_t count);
extern int64_t smb2_lseek(struct smb2_context *smb2, struct smb2fh *fh, int64_t offset, int whence, uint64_t *current_offset);
extern int smb2_fstat(struct smb2_context *smb2, struct smb2fh *fh, struct smb2_stat_64 *st);
extern const char *smb2_get_error(struct smb2_context *smb2);

typedef struct {
    struct smb2_context *smb2;
    struct smb2fh *fh;
    int64_t fileSize;
    _Atomic(bool) *interrupted;
} EDSMBContext;

static int ed_smb_read(void *opaque, uint8_t *buf, int buf_size) {
    EDSMBContext *ctx = (EDSMBContext *)opaque;
    if (!ctx || !ctx->smb2 || !ctx->fh) return AVERROR(EINVAL);
    if (ctx->interrupted && atomic_load(ctx->interrupted)) return AVERROR_EXIT;
    int n = smb2_read(ctx->smb2, ctx->fh, buf, (uint32_t)buf_size);
    if (n == 0) return AVERROR_EOF;
    if (n < 0) return AVERROR(EIO);
    return n;
}

static int64_t ed_smb_seek(void *opaque, int64_t offset, int whence) {
    EDSMBContext *ctx = (EDSMBContext *)opaque;
    if (!ctx || !ctx->smb2 || !ctx->fh) return AVERROR(EINVAL);
    if (ctx->interrupted && atomic_load(ctx->interrupted)) return AVERROR_EXIT;
    if (whence == AVSEEK_SIZE) {
        return ctx->fileSize >= 0 ? ctx->fileSize : AVERROR(ENOSYS);
    }
    int64_t res = smb2_lseek(ctx->smb2, ctx->fh, offset, whence, NULL);
    if (res < 0) return AVERROR(EIO);
    return res;
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

@implementation EDFFmpegReader {
    AVFormatContext *_format;
    AVIOContext *_customIO;
    int _fileFD;
    EDSMBContext *_smbContext;
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
}

static BOOL EDReaderError(NSError **error, NSString *operation, int code) {
    if (error) {
        *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:code userInfo:@{
            NSLocalizedDescriptionKey: [NSString stringWithFormat:@"%@: %@", operation, edendale_av_err2str(code)]
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
        atomic_init(&_interrupted, false);
        atomic_init(&_deadline, 0);
    }
    return self;
}

- (void)interrupt { atomic_store(&_interrupted, true); }
- (BOOL)atEnd { return _drained; }

- (void)cleanupSMBContext {
    if (_smbContext) {
        if (_smbContext->fh) {
            smb2_close(_smbContext->smb2, _smbContext->fh);
            _smbContext->fh = NULL;
        }
        if (_smbContext->smb2) {
            smb2_disconnect_share(_smbContext->smb2);
            smb2_destroy_context(_smbContext->smb2);
            _smbContext->smb2 = NULL;
        }
        free(_smbContext);
        _smbContext = NULL;
    }
}

- (void)close {
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
    [self cleanupSMBContext];
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
    if (_video) {
        avcodec_free_context(&_video);
        _video = NULL;
    }
    NSError *err = nil;
    _video = [self openCodec:_videoIndex hardware:_hardwareDecoding error:&err];
    if (!_video && _hardwareDecoding) {
        NSLog(@"[FFmpegReader] Hardware video decoder creation failed; falling back to software: %@", err);
        _video = [self openCodec:_videoIndex hardware:NO error:&err];
    }
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

- (BOOL)openURL:(NSURL *)url error:(NSError **)error {
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
    } else if ([url.scheme.lowercaseString isEqualToString:@"smb"] || [url.scheme.lowercaseString isEqualToString:@"smb2"]) {
        NSString *host = url.host;
        if (!host || host.length == 0) {
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Invalid SMB URL: missing host", AVERROR(EINVAL));
        }
        NSNumber *port = url.port;
        NSString *server = (port && port.intValue > 0) ? [NSString stringWithFormat:@"%@:%@", host, port] : host;

        NSString *rawUser = url.user;
        NSString *domain = nil;
        NSString *user = rawUser;
        if (user.length > 0) {
            NSRange semi = [user rangeOfString:@";"];
            if (semi.location != NSNotFound) {
                domain = [user substringToIndex:semi.location];
                user = [user substringFromIndex:semi.location + 1];
            } else {
                NSRange backslash = [user rangeOfString:@"\\"];
                if (backslash.location != NSNotFound) {
                    domain = [user substringToIndex:backslash.location];
                    user = [user substringFromIndex:backslash.location + 1];
                }
            }
        }
        NSString *password = url.password;

        NSArray *pathSegments = [url.path componentsSeparatedByString:@"/"];
        NSMutableArray *cleanSegments = [NSMutableArray array];
        for (NSString *seg in pathSegments) {
            if (seg.length > 0) {
                [cleanSegments addObject:seg];
            }
        }
        if (cleanSegments.count < 2) {
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Invalid SMB URL: missing share or file path", AVERROR(EINVAL));
        }
        NSString *share = cleanSegments[0];
        [cleanSegments removeObjectAtIndex:0];
        NSString *filePath = [cleanSegments componentsJoinedByString:@"/"];

        struct smb2_context *smb2 = smb2_init_context();
        if (!smb2) {
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Allocate SMB context", AVERROR(ENOMEM));
        }

        smb2_set_timeout(smb2, 10);
        if (domain.length > 0) smb2_set_domain(smb2, domain.UTF8String);
        if (password.length > 0) smb2_set_password(smb2, password.UTF8String);
        const char *userStr = user.length > 0 ? user.UTF8String : NULL;

        int rc = smb2_connect_share(smb2, server.UTF8String, share.UTF8String, userStr);
        if (rc < 0) {
            const char *errStr = smb2_get_error(smb2);
            NSString *msg = (errStr && strlen(errStr) > 0)
                ? [NSString stringWithFormat:@"SMB connect failed: %s", errStr]
                : @"SMB connect failed";
            smb2_destroy_context(smb2);
            atomic_store(&_deadline, 0);
            [self close];
            if (error) {
                *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:rc userInfo:@{
                    NSLocalizedDescriptionKey: msg
                }];
            }
            return NO;
        }

        struct smb2fh *fh = smb2_open(smb2, filePath.UTF8String, O_RDONLY);
        if (!fh) {
            const char *errStr = smb2_get_error(smb2);
            NSString *msg = (errStr && strlen(errStr) > 0)
                ? [NSString stringWithFormat:@"SMB open failed: %s", errStr]
                : @"SMB open failed";
            smb2_disconnect_share(smb2);
            smb2_destroy_context(smb2);
            atomic_store(&_deadline, 0);
            [self close];
            if (error) {
                *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:AVERROR(ENOENT) userInfo:@{
                    NSLocalizedDescriptionKey: msg
                }];
            }
            return NO;
        }

        int64_t fileSize = -1;
        struct smb2_stat_64 st;
        if (smb2_fstat(smb2, fh, &st) == 0) {
            fileSize = (int64_t)st.smb2_size;
        }

        _smbContext = (EDSMBContext *)calloc(1, sizeof(EDSMBContext));
        if (!_smbContext) {
            smb2_close(smb2, fh);
            smb2_disconnect_share(smb2);
            smb2_destroy_context(smb2);
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Allocate SMB context memory", AVERROR(ENOMEM));
        }
        _smbContext->smb2 = smb2;
        _smbContext->fh = fh;
        _smbContext->fileSize = fileSize;
        _smbContext->interrupted = &_interrupted;

        static const int kSMBIOBufSize = 65536;
        unsigned char *ioBuf = av_malloc(kSMBIOBufSize);
        if (!ioBuf) {
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Allocate SMB I/O buffer", AVERROR(ENOMEM));
        }
        _customIO = avio_alloc_context(ioBuf, kSMBIOBufSize, 0,
                                       _smbContext,
                                       ed_smb_read, NULL, ed_smb_seek);
        if (!_customIO) {
            av_free(ioBuf);
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Allocate SMB I/O context", AVERROR(ENOMEM));
        }
        _format->pb = _customIO;
        _format->flags |= AVFMT_FLAG_CUSTOM_IO;
        location = filePath.lastPathComponent.UTF8String ?: "media";
        if (atomic_load(&_interrupted)) {
            atomic_store(&_deadline, 0);
            [self close];
            return EDReaderError(error, @"Playback cancelled", AVERROR_EXIT);
        }
    } else {
        location = url.absoluteString.UTF8String;
    }

    int result = avformat_open_input(&_format, location, NULL, NULL);
    if (result >= 0) result = avformat_find_stream_info(_format, NULL);
    atomic_store(&_deadline, 0);
    if (result < 0) { [self close]; return EDReaderError(error, @"Could not open media", result); }

    _origin = _format->start_time == AV_NOPTS_VALUE ? 0 : (double)_format->start_time / AV_TIME_BASE;
    _seekFloor = _videoNextTime = _audioNextTime = 0;
    _drained = NO;
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

- (BOOL)decode:(AVCodecContext *)codec packet:(AVPacket *)packet into:(NSMutableArray *)outputs error:(NSError **)error {
    int result = avcodec_send_packet(codec, packet);
    if (result < 0 && result != AVERROR_EOF) {
        if (codec == _video) {
            NSLog(@"[FFmpegReader] Video packet submit failed (err=%d). Attempting decoder recreation...", result);
            if ([self recreateVideoDecoder]) {
                result = avcodec_send_packet(_video, packet);
            }
            if (result < 0 && result != AVERROR_EOF && _hardwareDecoding) {
                NSLog(@"[FFmpegReader] Hardware retry failed (err=%d). Falling back to software decoder...", result);
                if (_video) {
                    avcodec_free_context(&_video);
                    _video = [self openCodec:_videoIndex hardware:NO error:nil];
                    if (_video) {
                        result = avcodec_send_packet(_video, packet);
                    }
                }
            }
            if (result < 0 && result != AVERROR_EOF) {
                // Drop this unrecoverable video frame rather than failing the batch,
                // which would terminate audio and abort the playback session.
                NSLog(@"[FFmpegReader] Dropping unrecoverable video packet (err=%d)", result);
                return YES;
            }
        } else {
            return EDReaderError(error, @"Submit media packet", result);
        }
    }
    while ((result = avcodec_receive_frame(codec, _frame)) >= 0) {
        NSError *conversionError = nil;
        EDFFmpegFrame *output = codec == _video ? [self videoFrameWithError:&conversionError] : [self audioFrameWithError:&conversionError];
        av_frame_unref(_frame);
        if (conversionError) {
            if (codec == _video) {
                NSLog(@"[FFmpegReader] Failed to convert video frame (%@); skipping frame", conversionError);
                continue;
            }
            if (error) *error = conversionError;
            return NO;
        }
        if (output) [outputs addObject:output];
    }
    if (codec == _video) {
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
            if (_video && _videoDecodingEnabled && ![self decode:_video packet:NULL into:outputs error:error]) return nil;
            if (_audio && ![self decode:_audio packet:NULL into:outputs error:error]) return nil;
            _drained = YES;
            return outputs;
        }
        if (result < 0) { EDReaderError(error, @"Read media packet", result); return nil; }
        if (_subtitle && _packet->stream_index == _subtitleIndex) {
            [self decodeSubtitle:_packet into:outputs];
            av_packet_unref(_packet);
            continue;
        }
        if (_av1Format && _packet->stream_index == _videoIndex) {
            if (_videoDecodingEnabled) [self decodeAV1Packet:_packet into:outputs];
            av_packet_unref(_packet);
            continue;
        }
        AVCodecContext *codec = _packet->stream_index == _videoIndex ? _video :
            (_packet->stream_index == _audioIndex ? _audio : NULL);
        if (codec == _video && !_videoDecodingEnabled) {
            av_packet_unref(_packet);
            continue;
        }
        if (codec && codec == _video) {
            // Frames before a seek target only rebuild the references of the
            // frames after it and are then dropped, so skip the ones no other
            // frame refers to (as mpv's precise seeks do). Hardware decoding
            // runs one frame at a time, so this shortens the post-seek freeze.
            _video->skip_frame = [self packetPrecedesSeekFloor:_packet] ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
        }
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
    if (_subtitle) avcodec_flush_buffers(_subtitle);
    _av1NeedsKeyframe = YES;
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

//
//  FFmpegBridge.m
//  Edendale
//
//  C/Objective-C interop helpers for FFmpeg and VideoToolbox.
//

#import "FFmpegBridge.h"
#import <FFmpeg/libavcodec/avcodec.h>
#import <FFmpeg/libavformat/avformat.h>
#import <FFmpeg/libavutil/hwcontext.h>
#import <FFmpeg/libavutil/hwcontext_videotoolbox.h>
#import <FFmpeg/libavutil/error.h>
#import <FFmpeg/libavutil/imgutils.h>
#import <FFmpeg/libswscale/swscale.h>
#import <VideoToolbox/VideoToolbox.h>

static enum AVPixelFormat edendale_get_hw_format(AVCodecContext *ctx, const enum AVPixelFormat *pix_fmts) {
    const enum AVPixelFormat *p;
    for (p = pix_fmts; *p != -1; p++) {
        if (*p == AV_PIX_FMT_VIDEOTOOLBOX) {
            return *p;
        }
    }
    return pix_fmts[0];
}

int edendale_setup_videotoolbox(struct AVCodecContext *ctx) {
    if (!ctx) return -1;
    AVBufferRef *hw_device_ctx = NULL;
    int err = av_hwdevice_ctx_create(&hw_device_ctx, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, NULL, NULL, 0);
    if (err < 0) {
        return err;
    }
    ctx->hw_device_ctx = hw_device_ctx;
    ctx->get_format = edendale_get_hw_format;
    return 0;
}

CVPixelBufferRef _Nullable edendale_frame_get_pixel_buffer(struct AVFrame *frame) {
    if (!frame) return NULL;
    if (frame->format == AV_PIX_FMT_VIDEOTOOLBOX && frame->data[3] != NULL) {
        CVPixelBufferRef pixelBuffer = (CVPixelBufferRef)frame->data[3];
        return CVPixelBufferRetain(pixelBuffer);
    }
    return NULL;
}

CVPixelBufferRef _Nullable edendale_create_pixel_buffer_from_sw_frame(
    struct AVFrame *frame,
    struct SwsContext * _Nullable * _Nonnull sws_ctx_ptr
) {
    if (!frame || frame->width <= 0 || frame->height <= 0) return NULL;

    int width = frame->width;
    int height = frame->height;

    NSDictionary *pixelAttributes = @{
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        (id)kCVPixelBufferMetalCompatibilityKey: @YES
    };

    CVPixelBufferRef pixelBuffer = NULL;
    CVReturn status = CVPixelBufferCreate(
        kCFAllocatorDefault,
        width,
        height,
        kCVPixelFormatType_32BGRA,
        (__bridge CFDictionaryRef)pixelAttributes,
        &pixelBuffer
    );

    if (status != kCVReturnSuccess || !pixelBuffer) {
        return NULL;
    }

    *sws_ctx_ptr = sws_getCachedContext(
        *sws_ctx_ptr,
        width,
        height,
        (enum AVPixelFormat)frame->format,
        width,
        height,
        AV_PIX_FMT_BGRA,
        SWS_BILINEAR,
        NULL,
        NULL,
        NULL
    );

    if (!*sws_ctx_ptr) {
        CVPixelBufferRelease(pixelBuffer);
        return NULL;
    }

    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    uint8_t *dst_data[4] = { (uint8_t *)CVPixelBufferGetBaseAddress(pixelBuffer), 0, 0, 0 };
    int dst_linesize[4] = { (int)CVPixelBufferGetBytesPerRow(pixelBuffer), 0, 0, 0 };

    sws_scale(
        *sws_ctx_ptr,
        (const uint8_t * const *)frame->data,
        frame->linesize,
        0,
        height,
        dst_data,
        dst_linesize
    );

    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    return pixelBuffer;
}

NSString *edendale_av_err2str(int errnum) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(errnum, errbuf, sizeof(errbuf));
    return [NSString stringWithUTF8String:errbuf];
}

int edendale_averror_eof(void) {
    return AVERROR_EOF;
}

int edendale_averror_eagain(void) {
    return AVERROR(EAGAIN);
}

int edendale_seek_flag_backward(void) {
    return AVSEEK_FLAG_BACKWARD;
}

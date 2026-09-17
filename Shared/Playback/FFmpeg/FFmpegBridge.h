//
//  FFmpegBridge.h
//  Edendale
//
//  C/Objective-C interop helpers for FFmpeg and VideoToolbox.
//

#import <Foundation/Foundation.h>
#import <CoreVideo/CoreVideo.h>
#import <CoreMedia/CoreMedia.h>

#ifdef __cplusplus
extern "C" {
#endif

// Forward declarations of FFmpeg C types to avoid header pollution where unnecessary
struct AVCodecContext;
struct AVFrame;
struct SwsContext;

/// Initialize VideoToolbox hardware acceleration on the provided codec context.
/// Returns 0 on success, or an FFmpeg error code.
int edendale_setup_videotoolbox(struct AVCodecContext * _Nonnull ctx);

/// Returns a retained CVPixelBufferRef from an AVFrame decoded via VideoToolbox.
/// Returns NULL if the frame does not contain a VideoToolbox pixel buffer.
CVPixelBufferRef _Nullable edendale_frame_get_pixel_buffer(struct AVFrame * _Nonnull frame);

/// Converts a software-decoded AVFrame (e.g. YUV420P, YUV420P10, etc.) into a CVPixelBuffer.
/// Reuses or updates the SwsContext pointer passed in `sws_ctx_ptr`.
CVPixelBufferRef _Nullable edendale_create_pixel_buffer_from_sw_frame(
    struct AVFrame * _Nonnull frame,
    struct SwsContext * _Nullable * _Nonnull sws_ctx_ptr
);

/// Returns an NSString description of an FFmpeg error number.
NSString * _Nonnull edendale_av_err2str(int errnum);

/// Helper constants for FFmpeg error and seek flags
int edendale_averror_eof(void);
int edendale_averror_eagain(void);
int edendale_seek_flag_backward(void);

#ifdef __cplusplus
}
#endif

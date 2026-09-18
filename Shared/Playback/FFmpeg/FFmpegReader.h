#import <Foundation/Foundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>

NS_ASSUME_NONNULL_BEGIN

/// Immutable output owned independently of FFmpeg's reusable decoding frames.
@interface EDFFmpegFrame : NSObject
@property (nonatomic, readonly, nullable) CVPixelBufferRef pixelBuffer;
@property (nonatomic, readonly, nullable) CMSampleBufferRef audioSampleBuffer;
@property (nonatomic, readonly) double presentationTime;
@property (nonatomic, readonly) double duration;
/// Decoded subtitle cue, owned independently of AVSubtitle's temporary buffers.
@property (nonatomic, readonly, nullable) NSDictionary<NSString *, id> *subtitle;
@end

/// All operations run on one worker queue, except interrupt, which is thread-safe.
/// FFmpeg pointers never cross into the UI or escape the reader's lifetime.
@interface EDFFmpegReader : NSObject
- (instancetype)initWithHardwareDecoding:(BOOL)hardwareDecoding;
@property (nonatomic, readonly) NSDictionary<NSString *, id> *mediaInfo;
@property (nonatomic, readonly) BOOL atEnd;
@property (nonatomic, assign) BOOL videoDecodingEnabled;
- (BOOL)recreateVideoDecoder;
- (BOOL)openURL:(NSURL *)url error:(NSError **)error NS_SWIFT_NAME(open(url:));
/// Returns an empty batch at EOF, after draining both codecs; nil indicates error.
- (nullable NSArray<EDFFmpegFrame *> *)readBatchWithError:(NSError **)error;
- (BOOL)seekToSeconds:(double)seconds error:(NSError **)error NS_SWIFT_NAME(seek(seconds:));
- (BOOL)selectAudioTrack:(NSInteger)index error:(NSError **)error;
/// Select a subtitle decoder (-1 disables). Returns format/header configuration.
/// Cues are decoded incrementally by readBatch along with audio and video.
- (nullable NSDictionary<NSString *, id> *)selectSubtitleTrack:(NSInteger)index error:(NSError **)error;
- (void)interrupt;
- (void)close;
@end

NS_ASSUME_NONNULL_END

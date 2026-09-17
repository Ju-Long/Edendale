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
@end

/// All operations run on one worker queue, except interrupt, which is thread-safe.
/// FFmpeg pointers never cross into the UI or escape the reader's lifetime.
@interface EDFFmpegReader : NSObject
- (instancetype)initWithHardwareDecoding:(BOOL)hardwareDecoding;
@property (nonatomic, readonly) NSDictionary<NSString *, id> *mediaInfo;
@property (nonatomic, readonly) BOOL atEnd;
- (BOOL)openURL:(NSURL *)url error:(NSError **)error NS_SWIFT_NAME(open(url:));
/// Returns an empty batch at EOF, after draining both codecs; nil indicates error.
- (nullable NSArray<EDFFmpegFrame *> *)readBatchWithError:(NSError **)error;
- (BOOL)seekToSeconds:(double)seconds error:(NSError **)error NS_SWIFT_NAME(seek(seconds:));
- (BOOL)selectAudioTrack:(NSInteger)index error:(NSError **)error;
- (void)interrupt;
- (void)close;
@end

NS_ASSUME_NONNULL_END

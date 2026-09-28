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

/// Random-access bytes for FFmpeg custom I/O: remote files streamed over
/// HTTP (RemoteByteSource), NFS, or SFTP. Reads run on the reader's worker
/// queue and may block; -cancel is thread-safe and fails blocked reads.
NS_SWIFT_NAME(ByteSource)
@protocol EDByteSource <NSObject>
/// Total size in bytes, or -1 while unknown.
@property (nonatomic, readonly) int64_t length;
/// Copies up to `length` bytes at `offset` into `buffer`, blocking while the
/// data is fetched. Returns the byte count, 0 at the end of the file, or -1
/// on failure. `shouldAbort` is polled while waiting; YES fails the read,
/// and later reads proceed normally.
- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
              shouldAbort:(BOOL (NS_NOESCAPE ^)(void))shouldAbort
    NS_SWIFT_NAME(read(atOffset:into:length:shouldAbort:));
/// Stops all fetching; every later read fails.
- (void)cancel;
/// Why the last read failed, for the player's error message. Never contains
/// a URL, token, or password.
@property (nonatomic, readonly, nullable, copy) NSString *failureReason;
@end

/// All operations run on one worker queue, except interrupt, which is thread-safe.
/// FFmpeg pointers never cross into the UI or escape the reader's lifetime.
@interface EDFFmpegReader : NSObject
- (instancetype)initWithHardwareDecoding:(BOOL)hardwareDecoding;
@property (nonatomic, readonly) NSDictionary<NSString *, id> *mediaInfo;
@property (nonatomic, readonly) BOOL atEnd;
@property (nonatomic, assign) BOOL videoDecodingEnabled;
/// Replaces the video decoder, for example after iOS invalidated its hardware
/// session in the background. The new decoder starts at the next keyframe.
- (BOOL)recreateVideoDecoder;
/// Opens a local file or an `smb://` URL carrying its login. Other remote
/// schemes are rejected: they stream through -openByteSource:, so no token
/// or signed link ever reaches FFmpeg's own protocols (whose https doesn't
/// verify certificates by default).
- (BOOL)openURL:(NSURL *)url error:(NSError **)error NS_SWIFT_NAME(open(url:));
/// Opens media read through `source`. `name` is the file name, which FFmpeg
/// uses as a probing hint. Closing the reader cancels the source.
- (BOOL)openByteSource:(id<EDByteSource>)source name:(NSString *)name error:(NSError **)error
    NS_SWIFT_NAME(open(byteSource:name:));
/// Returns an empty batch at EOF, after draining both codecs; nil indicates error.
- (nullable NSArray<EDFFmpegFrame *> *)readBatchWithError:(NSError **)error;
/// With `decodeVideo` NO, video packets are held back undecoded, so the audio
/// the demuxer interleaves behind them can still be read. Held packets decode
/// first, in order, once video is decoded again.
- (nullable NSArray<EDFFmpegFrame *> *)readBatchDecodingVideo:(BOOL)decodeVideo error:(NSError **)error
    NS_SWIFT_NAME(readBatch(decodingVideo:));
/// Whether the last read stopped at held video, which must be decoded before
/// reading can continue.
@property (nonatomic, readonly) BOOL blockedOnVideo;
- (BOOL)seekToSeconds:(double)seconds error:(NSError **)error NS_SWIFT_NAME(seek(seconds:));
- (BOOL)selectAudioTrack:(NSInteger)index error:(NSError **)error;
/// Select a subtitle decoder (-1 disables). Returns format/header configuration.
/// Cues are decoded incrementally by readBatch along with audio and video.
- (nullable NSDictionary<NSString *, id> *)selectSubtitleTrack:(NSInteger)index error:(NSError **)error;
- (void)interrupt;
- (void)close;
@end

NS_ASSUME_NONNULL_END

//
//  EDBufferedByteSource.h
//  Edendale
//
//  An EDByteSource over a blocking file connection (SMB, see EDSMBFile;
//  NFS and SFTP, see RemoteFileByteSource.swift) that reads ahead on its
//  own thread and survives dropped connections.
//
//  FFmpeg asks for 64 KiB at a time. Fetching each of those over the
//  network costs a round trip, which over a phone hotspot or a VPN caps
//  throughput below a typical video bit rate. Here a worker thread fetches
//  1 MiB chunks and keeps up to 48 MiB ahead of the read position, so
//  FFmpeg's reads are served from memory and a stall shorter than the
//  buffer never reaches the player. The chunk a blocked read needs always
//  goes first, so seeks don't wait behind the read-ahead.
//
//  When a fetch fails after the file opened once, the connection is
//  dropped and reopened with backoff; the read fails only after every retry
//  has. An idle connection (paused playback) gets a keep-alive so the
//  server, or a NAT on the way, doesn't drop it.
//
//  The 48 MiB is what every device can spare. While the whole app uses less
//  than 500 MiB the worker reads on, up to 384 MiB ahead, so a light player
//  (a phone-sized picture, sound alone) turns its spare memory into minutes
//  of buffer. Once the app is past that it reads no further, and well past
//  it the extra is given back, farthest chunks first.
//
//  A link slower than the video still empties the buffer. The source says
//  how much it holds past the read position, so the player can stop and
//  refill instead of running ahead of its data (see RebufferPolicy).
//

#import <Foundation/Foundation.h>
#import "FFmpegReader.h"

NS_ASSUME_NONNULL_BEGIN

/// One open remote file. Used from one thread at a time; dropping the last
/// reference closes the connection.
NS_SWIFT_NAME(BufferedFile)
@protocol EDBufferedFile <NSObject>
/// Size in bytes, or -1 when the server didn't report it.
@property (nonatomic, readonly) int64_t size;
/// Returns the byte count, 0 at the end of the file, or -1 with `error` set.
- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
                    error:(NSError **)error;
/// A cheap round trip on an idle connection. NO means it's dead.
- (BOOL)keepAlive;
@optional
/// Fails a call in progress, from another thread, when the transport
/// allows it. Called when the source is cancelled.
- (void)abort;
@end

/// The memory the app may still take, in bytes; negative once it is past
/// what it should use. Called on the source's worker thread.
typedef int64_t (^EDMemoryHeadroom)(void);

/// Connects and opens the file; runs on the source's worker thread. Returns
/// nil with `error` set (its localized description is shown to the user).
typedef id<EDBufferedFile> _Nullable (^EDBufferedFileOpener)(NSError **error);

NS_SWIFT_NAME(BufferedByteSource)
@interface EDBufferedByteSource : NSObject <EDByteSource>

/// - Parameters:
///   - host: Named in the message when the connection is lost for good.
///   - opener: Called at the first read and again to reconnect.
- (instancetype)initWithHost:(NSString *)host opener:(EDBufferedFileOpener)opener NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

// Tuning, read at the first read. Tests shrink these.

/// Bytes per fetch. Default 1 MiB.
@property (nonatomic) NSInteger chunkSize;
/// How far past the read position to fetch, whatever memory the app uses.
/// Default 48 MiB.
@property (nonatomic) NSInteger readAheadBytes;
/// How far to fetch while the app uses less than memoryTarget; never less
/// than readAheadBytes. Default 384 MiB.
@property (nonatomic) NSInteger maxReadAheadBytes;
/// The app memory (its footprint, as Xcode's gauge shows it) up to which
/// the read-ahead grows past readAheadBytes. Default 500 MiB.
@property (nonatomic) int64_t memoryTarget;
/// Replaces the measurement behind memoryTarget. Tests set it.
@property (nonatomic, copy, nullable) EDMemoryHeadroom memoryHeadroom;
/// readAheadBytes plus what is kept outside the read-ahead (just behind
/// the read position, or where reading was before a seek); the difference
/// is at least two chunks. Default 64 MiB.
@property (nonatomic) NSInteger cacheBytes;
/// Delays before each reconnect after a failure; their count is the number
/// of retries. Default 0.25, 0.5, 1, 2, 4, 8 s.
@property (nonatomic, copy) NSArray<NSNumber *> *retryDelays;
/// Idle time before a keep-alive; 0 disables it. Default 20 s.
@property (nonatomic) NSTimeInterval keepAliveInterval;

/// The bytes fetched past the end of the last read, for the player's
/// rebuffering (see EDByteSource). `complete` once the read-ahead for that
/// position is all here, or has given up.
- (int64_t)bufferedBytesAheadComplete:(BOOL *)complete NS_SWIFT_NAME(bufferedBytesAhead(complete:));

@end

NS_ASSUME_NONNULL_END

//
//  EDBufferedByteSource.m
//  Edendale
//

#import "EDBufferedByteSource.h"
#import <TargetConditionals.h>
#import <mach/mach.h>
#if !TARGET_OS_OSX
#import <os/proc.h>
#endif

/// The state the worker thread and FFmpeg's reads share. The thread keeps
/// it alive until it exits, so the public object's dealloc can cancel it.
@interface EDBufferedByteSourceCore : NSObject {
@public
    NSInteger _chunkSize;
    NSInteger _readAheadBytes;
    NSInteger _maxReadAheadBytes;
    NSInteger _cacheBytes;
    int64_t _memoryTarget;
    EDMemoryHeadroom _memoryHeadroom;
    NSArray<NSNumber *> *_retryDelays;
    NSTimeInterval _keepAliveInterval;
}
- (instancetype)initWithHost:(NSString *)host opener:(EDBufferedFileOpener)opener;
@property (nonatomic, readonly) int64_t length;
@property (nonatomic, readonly, nullable) NSString *failureReason;
- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
              shouldAbort:(BOOL (NS_NOESCAPE ^)(void))shouldAbort;
- (int64_t)bufferedBytesAheadComplete:(BOOL *)complete;
- (void)cancel;
@end

@implementation EDBufferedByteSourceCore {
    NSString *_host;
    EDBufferedFileOpener _opener;

    // Guarded by _condition.
    NSCondition *_condition;
    NSMutableDictionary<NSNumber *, NSData *> *_chunks;
    BOOL _started;
    BOOL _cancelled;
    BOOL _opened;
    int64_t _size;
    /// The last chunk of the file, once the size or a short chunk shows it.
    int64_t _lastChunk;
    /// The chunk FFmpeg read last; read-ahead starts here.
    int64_t _readChunk;
    /// Where FFmpeg's last read ended.
    int64_t _readEnd;
    /// The app had memory to spare at the worker's last look, so the
    /// read-ahead may grow past `_readAheadBytes`.
    BOOL _roomy;
    /// Where reading was before it last jumped, or -1 once it has settled
    /// at the new place. What is buffered there stays meanwhile: FFmpeg
    /// jumps away for a moment (to a Matroska file's index at its end, on
    /// the first seek) and comes straight back.
    int64_t _heldChunk;
    /// Chunks read on from the place of the last jump.
    int64_t _sinceJump;
    /// The chunk a blocked read waits for, or -1.
    int64_t _wanted;
    /// A chunk whose fetch failed while a read waited for it, or -1.
    int64_t _failedChunk;
    /// The first open failed: every read fails.
    BOOL _fatal;
    /// A prefetch failed for good; the next read resumes read-ahead.
    BOOL _prefetchHalted;
    NSString *_failure;
    NSString *_reason;
    /// The worker's open file, for cancel() to abort.
    id<EDBufferedFile> _liveFile;

    // Worker thread only.
    /// When read-ahead was last given back, in uptime seconds.
    NSTimeInterval _lastTrim;
    id<EDBufferedFile> _file;
    BOOL _everOpened;
    NSTimeInterval _lastActivity;
}

- (instancetype)initWithHost:(NSString *)host opener:(EDBufferedFileOpener)opener {
    if ((self = [super init])) {
        _host = [host copy];
        _opener = [opener copy];
        _condition = [[NSCondition alloc] init];
        _chunks = [NSMutableDictionary dictionary];
        _size = -1;
        _lastChunk = INT64_MAX;
        _wanted = _failedChunk = _heldChunk = -1;
        _chunkSize = 1 << 20;
        _readAheadBytes = 48 << 20;
        _maxReadAheadBytes = 384 << 20;
        _cacheBytes = 64 << 20;
        _memoryTarget = (int64_t)1200 << 20; // 1.2 GB
        _retryDelays = @[@0.25, @0.5, @1, @2, @4, @8];
        _keepAliveInterval = 20;
    }
    return self;
}

static NSTimeInterval EDNow(void) {
    return NSProcessInfo.processInfo.systemUptime;
}

#if !TARGET_OS_OSX
/// Memory left free below the app's limit.
static const int64_t EDMemoryReserve = 256 << 20;
#endif

/// The app may pass its memory target by this much before read-ahead that
/// grew into spare memory is given back. Keeps growing and trimming apart.
static const int64_t EDMemoryTrimSlack = 32 << 20;

/// Seconds between two trims. Freed memory leaves the app's footprint a few
/// seconds late; trimming again before it has would give back too much.
static const NSTimeInterval EDMemoryTrimInterval = 15;

/// Reading this many chunks on from where it jumped to has settled there.
static const int64_t EDJumpSettleChunks = 8;

- (int64_t)aheadChunks {
    return MAX(1, _readAheadBytes / _chunkSize);
}

- (int64_t)maxAheadChunks {
    return MAX([self aheadChunks], _maxReadAheadBytes / _chunkSize);
}

/// How far the worker reads ahead right now.
- (int64_t)aheadLimitLocked {
    return _roomy ? [self maxAheadChunks] : [self aheadChunks];
}

/// Chunks kept outside the read-ahead: just behind the read position, or
/// left where reading was before a seek.
- (int64_t)spareChunks {
    return MAX(2, (_cacheBytes - _readAheadBytes) / _chunkSize);
}

/// The memory the app may still take before it reaches `_memoryTarget`;
/// negative once it is past it. On iOS, tvOS and visionOS it also stays
/// clear of the limit the system sets the app.
- (int64_t)currentMemoryHeadroom {
    if (_memoryHeadroom) return _memoryHeadroom();
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) != KERN_SUCCESS) return 0;
    int64_t headroom = _memoryTarget - (int64_t)info.phys_footprint;
#if !TARGET_OS_OSX
    // 0 when the app is already over its limit.
    headroom = MIN(headroom, (int64_t)os_proc_available_memory() - EDMemoryReserve);
#endif
    return headroom;
}

#pragma mark Reads (FFmpeg's worker queue)

- (int64_t)length {
    [_condition lock];
    int64_t length = _opened ? _size : -1;
    [_condition unlock];
    return length;
}

- (NSString *)failureReason {
    [_condition lock];
    NSString *reason = _reason;
    [_condition unlock];
    return reason;
}

- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
              shouldAbort:(BOOL (NS_NOESCAPE ^)(void))shouldAbort {
    if (length <= 0 || offset < 0) return 0;
    [_condition lock];
    [self startIfNeededLocked];
    int64_t index = offset / _chunkSize;
    if (_readChunk != index || _prefetchHalted) {
        if (index == _readChunk + 1) {
            if (_heldChunk >= 0 && ++_sinceJump >= EDJumpSettleChunks) _heldChunk = -1;
        } else if (index != _readChunk) {
            _heldChunk = _readChunk;
            _sinceJump = 0;
        }
        _readChunk = index;
        _prefetchHalted = NO;
        // Chunks now behind the read position go at once, whether or not
        // the worker has anything to fetch.
        [self evictLocked];
        [_condition broadcast];
    }
    NSInteger result;
    for (;;) {
        if (_cancelled) { result = -1; break; }
        if (_fatal) { _reason = _failure; result = -1; break; }
        if (_opened && _size >= 0 && offset >= _size) { result = 0; break; }
        NSData *chunk = _chunks[@(index)];
        if (chunk) {
            int64_t start = offset - index * _chunkSize;
            // A chunk shorter than the offset ends the file.
            if (start >= (int64_t)chunk.length) { result = 0; break; }
            NSInteger count = MIN(length, (NSInteger)((int64_t)chunk.length - start));
            memcpy(buffer, (const uint8_t *)chunk.bytes + start, (size_t)count);
            result = count;
            break;
        }
        if (_failedChunk == index) {
            _failedChunk = -1;
            _reason = _failure;
            result = -1;
            break;
        }
        if (_wanted != index) {
            _wanted = index;
            [_condition broadcast];
        }
        if (shouldAbort()) { result = -1; break; }
        [_condition waitUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
    }
    if (_wanted == index) _wanted = -1;
    if (result >= 0) _readEnd = offset + result;
    [_condition unlock];
    return result;
}

- (int64_t)bufferedBytesAheadComplete:(BOOL *)complete {
    [_condition lock];
    int64_t bytes = 0;
    int64_t index = _readEnd / _chunkSize;
    int64_t last = MIN(_lastChunk, _readChunk + [self maxAheadChunks]);
    for (; index <= last; index++) {
        NSData *chunk = _chunks[@(index)];
        if (!chunk) break;
        int64_t start = MAX(_readEnd - index * _chunkSize, 0);
        bytes += MAX((int64_t)chunk.length - start, 0);
    }
    // The last chunk the worker fetches for this read position, with the
    // memory there is now.
    int64_t end = MIN(_lastChunk, _readChunk + [self aheadLimitLocked]);
    // After a failure nothing more arrives until a read asks again.
    BOOL done = _cancelled || _fatal || _prefetchHalted || (_opened && index > end);
    [_condition unlock];
    if (complete) *complete = done;
    return bytes;
}

- (void)cancel {
    [_condition lock];
    _cancelled = YES;
    [_chunks removeAllObjects];
    id<EDBufferedFile> file = _liveFile;
    _liveFile = nil;
    [_condition broadcast];
    [_condition unlock];
    if ([file respondsToSelector:@selector(abort)]) [file abort];
}

/// Sets the worker's file, keeping cancel()'s reference in step.
- (void)setFile:(id<EDBufferedFile>)file {
    _file = file;
    [_condition lock];
    _liveFile = _cancelled ? nil : file;
    [_condition unlock];
}

- (void)startIfNeededLocked {
    if (_started || _cancelled) return;
    _started = YES;
    _chunkSize = MAX(_chunkSize, 1);
    _cacheBytes = MAX(_cacheBytes, _readAheadBytes + 2 * _chunkSize);
    _maxReadAheadBytes = MAX(_maxReadAheadBytes, _readAheadBytes);
    // The thread retains the core until it exits.
    NSThread *thread = [[NSThread alloc] initWithTarget:self selector:@selector(run) object:nil];
    thread.name = @"Edendale.BufferedByteSource";
    thread.qualityOfService = NSQualityOfServiceUserInitiated;
    [thread start];
}

#pragma mark Worker thread

- (void)run {
    for (;;) {
        @autoreleasepool {
            // Asked outside the lock: it is a call into the kernel.
            int64_t headroom = [self currentMemoryHeadroom];
            [_condition lock];
            _roomy = headroom > 0;
            if (headroom < -EDMemoryTrimSlack && EDNow() - _lastTrim > EDMemoryTrimInterval
                && [self trimLocked:-headroom]) {
                _lastTrim = EDNow();
            }
            int64_t target = _cancelled ? -1 : [self nextChunkLocked];
            BOOL keepAlive = NO;
            if (!_cancelled && target < 0) {
                NSTimeInterval wait = 3600;
                if (_file && _keepAliveInterval > 0) {
                    NSTimeInterval idle = EDNow() - _lastActivity;
                    if (idle >= _keepAliveInterval) keepAlive = YES;
                    else wait = _keepAliveInterval - idle;
                }
                // Any wake (a read moved on, a cancel, the timeout) goes
                // round again and looks at memory afresh.
                if (!keepAlive) [_condition waitUntilDate:[NSDate dateWithTimeIntervalSinceNow:wait]];
            }
            BOOL cancelled = _cancelled;
            [_condition unlock];
            if (cancelled) break;

            if (keepAlive) {
                // A dead connection reopens at the next fetch.
                if (![_file keepAlive]) [self setFile:nil];
                _lastActivity = EDNow();
            } else if (target >= 0) {
                [self fetchChunk:target];
            }
        }
    }
    // Closes the connection on the thread that used it.
    [self setFile:nil];
}

/// The chunk a read waits for, then the first one missing ahead of the
/// read position.
- (int64_t)nextChunkLocked {
    if (_fatal) return -1;
    if (_wanted >= 0 && _wanted != _failedChunk && !_chunks[@(_wanted)]) return _wanted;
    if (_prefetchHalted || !_opened) return -1;
    int64_t end = MIN(_lastChunk, _readChunk + [self aheadLimitLocked]);
    for (int64_t index = _readChunk; index <= end; index++) {
        if (index != _failedChunk && !_chunks[@(index)]) return index;
    }
    return -1;
}

- (BOOL)isCancelled {
    [_condition lock];
    BOOL cancelled = _cancelled;
    [_condition unlock];
    return cancelled;
}

- (void)fetchChunk:(int64_t)index {
    NSError *error = nil;
    NSData *data = nil;
    NSUInteger attempt = 0;
    for (;;) {
        if (!_file) {
            error = nil;
            [self setFile:_opener(&error)];
            if (_file) {
                _everOpened = YES;
                int64_t size = _file.size;
                [_condition lock];
                _opened = YES;
                _size = size;
                if (size >= 0) _lastChunk = size == 0 ? -1 : (size - 1) / _chunkSize;
                [_condition unlock];
            }
        }
        if (_file) {
            data = [self readChunk:index error:&error];
            _lastActivity = EDNow();
            if (data) break;
            // Drop the connection: libsmb2 can't recover a timed-out one.
            [self setFile:nil];
        }
        // A login or path that never worked won't work on a retry either.
        if (!_everOpened || attempt >= _retryDelays.count || [self isCancelled]) break;
        NSDate *until = [NSDate dateWithTimeIntervalSinceNow:_retryDelays[attempt].doubleValue];
        attempt++;
        [_condition lock];
        while (!_cancelled && until.timeIntervalSinceNow > 0) [_condition waitUntilDate:until];
        [_condition unlock];
    }

    [_condition lock];
    if (data) {
        _chunks[@(index)] = data;
        if ((NSInteger)data.length < _chunkSize) _lastChunk = MIN(_lastChunk, index);
        [self evictLocked];
    } else if (!_cancelled) {
        NSString *detail = error.localizedDescription;
        if (!_everOpened) {
            _fatal = YES;
            _failure = detail.length > 0 ? detail : [NSString stringWithFormat:NSLocalizedString(@"Couldn't connect to %@.", nil), _host];
        } else {
            _failure = detail.length > 0
                ? [NSString stringWithFormat:NSLocalizedString(@"Lost the connection to %@: %@", nil), _host, detail]
                : [NSString stringWithFormat:NSLocalizedString(@"Lost the connection to %@.", nil), _host];
            if (_wanted == index) _failedChunk = index;
            // Retrying again at once would only fail again; wait for a read.
            _prefetchHalted = YES;
        }
    }
    [_condition broadcast];
    [_condition unlock];
}

/// Reads one whole chunk, which may take several calls.
- (NSData *)readChunk:(int64_t)index error:(NSError **)error {
    int64_t offset = index * _chunkSize;
    int64_t want = _chunkSize;
    int64_t size = _file.size;
    if (size >= 0) want = MAX(0, MIN(want, size - offset));
    NSMutableData *data = [NSMutableData dataWithLength:(NSUInteger)want];
    uint8_t *bytes = data.mutableBytes;
    int64_t filled = 0;
    while (filled < want) {
        if ([self isCancelled]) return nil;
        NSError *readError = nil;
        NSInteger count = [_file readAtOffset:offset + filled
                                         into:bytes + filled
                                       length:(NSInteger)(want - filled)
                                        error:&readError];
        if (count < 0) {
            if (error) {
                *error = readError ?: [NSError errorWithDomain:NSPOSIXErrorDomain code:EIO userInfo:nil];
            }
            return nil;
        }
        if (count == 0) break;
        filled += count;
    }
    data.length = (NSUInteger)filled;
    return data;
}

/// Whether `index` is in the read-ahead of the read position, or of the
/// place reading has just jumped from.
- (BOOL)isHeldLocked:(int64_t)index {
    int64_t ahead = [self maxAheadChunks];
    if (index >= _readChunk && index <= _readChunk + ahead) return YES;
    return _heldChunk >= 0 && index >= _heldChunk && index <= _heldChunk + ahead;
}

/// Keeps the read-ahead and `spareChunks` more. Of the rest (chunks behind
/// the read position, or left where reading was before a seek) the
/// farthest go first, those behind before those ahead.
- (void)evictLocked {
    int64_t spare = [self spareChunks];
    for (;;) {
        NSNumber *victim = nil;
        int64_t worst = -1;
        int64_t outside = 0;
        for (NSNumber *key in _chunks) {
            int64_t index = key.longLongValue;
            if ([self isHeldLocked:index]) continue;
            outside++;
            int64_t distance = index < _readChunk ? (_readChunk - index) * 4 : index - _readChunk;
            if (distance > worst) {
                worst = distance;
                victim = key;
            }
        }
        if (outside <= spare || !victim) return;
        [_chunks removeObjectForKey:victim];
    }
}

/// Gives back `bytes` of the read-ahead that grew into spare memory: first
/// at the place reading jumped from, then here, the farthest chunks first.
/// The read-ahead of `_readAheadBytes` always stays. Returns whether there
/// was anything to give.
- (BOOL)trimLocked:(int64_t)bytes {
    int64_t keep = [self aheadChunks];
    int64_t ahead = [self maxAheadChunks];
    int64_t starts[2] = { _heldChunk, _readChunk };
    BOOL trimmed = NO;
    for (int i = 0; i < 2; i++) {
        if (starts[i] < 0) continue;
        for (int64_t index = starts[i] + ahead; index > starts[i] + keep && bytes > 0; index--) {
            NSNumber *key = @(index);
            NSData *chunk = _chunks[key];
            // The other place's read-ahead is not this one's to give.
            if (!chunk || (i == 0 && index >= _readChunk && index <= _readChunk + keep)) continue;
            bytes -= (int64_t)chunk.length;
            [_chunks removeObjectForKey:key];
            trimmed = YES;
        }
    }
    return trimmed;
}

@end

@implementation EDBufferedByteSource {
    EDBufferedByteSourceCore *_core;
}

- (instancetype)initWithHost:(NSString *)host opener:(EDBufferedFileOpener)opener {
    if ((self = [super init])) {
        _core = [[EDBufferedByteSourceCore alloc] initWithHost:host opener:opener];
    }
    return self;
}

- (void)dealloc {
    [_core cancel];
}

- (NSInteger)chunkSize { return _core->_chunkSize; }
- (void)setChunkSize:(NSInteger)chunkSize { _core->_chunkSize = chunkSize; }
- (NSInteger)readAheadBytes { return _core->_readAheadBytes; }
- (void)setReadAheadBytes:(NSInteger)bytes { _core->_readAheadBytes = bytes; }
- (NSInteger)maxReadAheadBytes { return _core->_maxReadAheadBytes; }
- (void)setMaxReadAheadBytes:(NSInteger)bytes { _core->_maxReadAheadBytes = bytes; }
- (int64_t)memoryTarget { return _core->_memoryTarget; }
- (void)setMemoryTarget:(int64_t)bytes { _core->_memoryTarget = bytes; }
- (EDMemoryHeadroom)memoryHeadroom { return _core->_memoryHeadroom; }
- (void)setMemoryHeadroom:(EDMemoryHeadroom)headroom { _core->_memoryHeadroom = [headroom copy]; }
- (NSInteger)cacheBytes { return _core->_cacheBytes; }
- (void)setCacheBytes:(NSInteger)bytes { _core->_cacheBytes = bytes; }
- (NSArray<NSNumber *> *)retryDelays { return _core->_retryDelays; }
- (void)setRetryDelays:(NSArray<NSNumber *> *)delays { _core->_retryDelays = [delays copy]; }
- (NSTimeInterval)keepAliveInterval { return _core->_keepAliveInterval; }
- (void)setKeepAliveInterval:(NSTimeInterval)interval { _core->_keepAliveInterval = interval; }

- (int64_t)length { return _core.length; }
- (NSString *)failureReason { return _core.failureReason; }
- (int64_t)bufferedBytesAheadComplete:(BOOL *)complete { return [_core bufferedBytesAheadComplete:complete]; }
- (void)cancel { [_core cancel]; }

- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
              shouldAbort:(BOOL (NS_NOESCAPE ^)(void))shouldAbort {
    return [_core readAtOffset:offset into:buffer length:length shouldAbort:shouldAbort];
}

@end

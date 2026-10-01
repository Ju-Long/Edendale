//
//  EDBufferedByteSource.m
//  Edendale
//

#import "EDBufferedByteSource.h"

/// The state the worker thread and FFmpeg's reads share. The thread keeps
/// it alive until it exits, so the public object's dealloc can cancel it.
@interface EDBufferedByteSourceCore : NSObject {
@public
    NSInteger _chunkSize;
    NSInteger _readAheadBytes;
    NSInteger _cacheBytes;
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
        _wanted = _failedChunk = -1;
        _chunkSize = 1 << 20;
        _readAheadBytes = 48 << 20;
        _cacheBytes = 64 << 20;
        _retryDelays = @[@0.25, @0.5, @1, @2, @4, @8];
        _keepAliveInterval = 20;
    }
    return self;
}

static NSTimeInterval EDNow(void) {
    return NSProcessInfo.processInfo.systemUptime;
}

- (int64_t)aheadChunks {
    return MAX(1, _readAheadBytes / _chunkSize);
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
        _readChunk = index;
        _prefetchHalted = NO;
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
    [_condition unlock];
    return result;
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
            [_condition lock];
            int64_t target = -1;
            BOOL keepAlive = NO;
            while (!_cancelled) {
                target = [self nextChunkLocked];
                if (target >= 0) break;
                NSTimeInterval wait = 3600;
                if (_file && _keepAliveInterval > 0) {
                    NSTimeInterval idle = EDNow() - _lastActivity;
                    if (idle >= _keepAliveInterval) { keepAlive = YES; break; }
                    wait = _keepAliveInterval - idle;
                }
                [_condition waitUntilDate:[NSDate dateWithTimeIntervalSinceNow:wait]];
            }
            BOOL cancelled = _cancelled;
            [_condition unlock];
            if (cancelled) break;

            if (keepAlive) {
                // A dead connection reopens at the next fetch.
                if (![_file keepAlive]) [self setFile:nil];
                _lastActivity = EDNow();
            } else {
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
    int64_t end = MIN(_lastChunk, _readChunk + [self aheadChunks]);
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
            _failure = detail.length > 0 ? detail : [NSString stringWithFormat:@"Couldn't connect to %@.", _host];
        } else {
            _failure = detail.length > 0
                ? [NSString stringWithFormat:@"Lost the connection to %@: %@", _host, detail]
                : [NSString stringWithFormat:@"Lost the connection to %@.", _host];
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

/// Drops the chunks farthest from the read position, those behind it first.
- (void)evictLocked {
    NSUInteger limit = (NSUInteger)MAX(_cacheBytes / _chunkSize, [self aheadChunks] + 2);
    while (_chunks.count > limit) {
        NSNumber *victim = nil;
        int64_t worst = -1;
        for (NSNumber *key in _chunks) {
            int64_t index = key.longLongValue;
            int64_t distance = index < _readChunk ? (_readChunk - index) * 4 : index - _readChunk;
            if (distance > worst) {
                worst = distance;
                victim = key;
            }
        }
        [_chunks removeObjectForKey:victim];
    }
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
- (NSInteger)cacheBytes { return _core->_cacheBytes; }
- (void)setCacheBytes:(NSInteger)bytes { _core->_cacheBytes = bytes; }
- (NSArray<NSNumber *> *)retryDelays { return _core->_retryDelays; }
- (void)setRetryDelays:(NSArray<NSNumber *> *)delays { _core->_retryDelays = [delays copy]; }
- (NSTimeInterval)keepAliveInterval { return _core->_keepAliveInterval; }
- (void)setKeepAliveInterval:(NSTimeInterval)interval { _core->_keepAliveInterval = interval; }

- (int64_t)length { return _core.length; }
- (NSString *)failureReason { return _core.failureReason; }
- (void)cancel { [_core cancel]; }

- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
              shouldAbort:(BOOL (NS_NOESCAPE ^)(void))shouldAbort {
    return [_core readAtOffset:offset into:buffer length:length shouldAbort:shouldAbort];
}

@end

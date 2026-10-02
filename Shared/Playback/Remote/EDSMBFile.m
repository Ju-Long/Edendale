//
//  EDSMBFile.m
//  Edendale
//

#import "EDSMBFile.h"
#import <fcntl.h>

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
extern void smb2_set_password(struct smb2_context *smb2, const char *password);
extern void smb2_set_domain(struct smb2_context *smb2, const char *domain);
extern void smb2_set_timeout(struct smb2_context *smb2, int seconds);
extern int smb2_connect_share(struct smb2_context *smb2, const char *server, const char *share, const char *user);
extern int smb2_disconnect_share(struct smb2_context *smb2);
extern struct smb2fh *smb2_open(struct smb2_context *smb2, const char *path, int flags);
extern int smb2_pread(struct smb2_context *smb2, struct smb2fh *fh, uint8_t *buf, uint32_t count, uint64_t offset);
extern int smb2_fstat(struct smb2_context *smb2, struct smb2fh *fh, struct smb2_stat_64 *st);
extern const char *smb2_get_error(struct smb2_context *smb2);

// The libsmb2 inside libvlc (6.1) needs care in its blocking calls:
//
//  - A call whose wait fails (the socket dropped, as it does across a lock
//    or a VPN reconnect) leaves its request queued, still pointing at the
//    caller's buffer, until the context is destroyed. Reads and the
//    keep-alive's query therefore land in memory this object owns and
//    frees only after destroying the context.
//  - smb2_close frees its callback data when its wait fails yet leaves the
//    request queued; destroying the context then frees it again, a heap
//    corruption that crashed on return from the background. It's never
//    called: disconnecting the share closes the file on the server.
//  - smb2_echo inverts its connection check and always fails, which
//    dropped every idle connection. The keep-alive queries the file instead.

/// Seconds a request may take before libsmb2 fails it. A stalled
/// connection is then dropped and reopened.
static const int EDSMBTimeout = 10;

/// The most one read asks for. Reads land in a buffer of this size first;
/// a longer request returns short and the caller asks again.
static const uint32_t EDSMBReadSize = 1 << 20;

static NSError *EDSMBError(NSString *operation, struct smb2_context *smb2, int code) {
    const char *detail = smb2 ? smb2_get_error(smb2) : NULL;
    NSString *message = (detail && detail[0])
        ? [NSString stringWithFormat:@"%@: %s", operation, detail]
        : operation;
    return [NSError errorWithDomain:@"Edendale.FFmpeg" code:code userInfo:@{NSLocalizedDescriptionKey: message}];
}

@implementation EDSMBFile {
    struct smb2_context *_smb2;
    struct smb2fh *_fh;
    int64_t _size;
    /// A call failed. Requests may still be queued, so the context is
    /// destroyed without another round trip.
    BOOL _broken;
    /// Where reads land (EDSMBReadSize bytes); see the note above.
    uint8_t *_buffer;
    /// Where the opening and keep-alive queries land.
    struct smb2_stat_64 _stat;
}

@synthesize size = _size;

- (instancetype)initWithServer:(NSString *)server
                         share:(NSString *)share
                          path:(NSString *)path
                          user:(NSString *)user
                        domain:(NSString *)domain
                      password:(NSString *)password
                         error:(NSError **)error {
    if (!(self = [super init])) return nil;
    _size = -1;
    _smb2 = smb2_init_context();
    if (!_smb2) {
        if (error) *error = EDSMBError(@"Allocate SMB context", NULL, -ENOMEM);
        return nil;
    }
    smb2_set_timeout(_smb2, EDSMBTimeout);
    if (domain.length > 0) smb2_set_domain(_smb2, domain.UTF8String);
    if (password.length > 0) smb2_set_password(_smb2, password.UTF8String);

    int rc = smb2_connect_share(_smb2, server.UTF8String, share.UTF8String,
                                user.length > 0 ? user.UTF8String : NULL);
    if (rc < 0) {
        _broken = YES;
        if (error) *error = EDSMBError(@"SMB connect failed", _smb2, rc);
        return nil;
    }
    _fh = smb2_open(_smb2, path.UTF8String, O_RDONLY);
    if (!_fh) {
        _broken = YES;
        if (error) *error = EDSMBError(@"SMB open failed", _smb2, -ENOENT);
        return nil;
    }
    _buffer = malloc(EDSMBReadSize);
    if (!_buffer) {
        if (error) *error = EDSMBError(@"Allocate SMB buffer", NULL, -ENOMEM);
        return nil;
    }
    // An unknown size still plays; a dead connection fails the first read.
    if (smb2_fstat(_smb2, _fh, &_stat) == 0) _size = (int64_t)_stat.smb2_size;
    return self;
}

- (void)dealloc {
    if (_smb2) {
        // A healthy connection logs off, which also closes the file on the
        // server. Any other one only closes its socket; the server then
        // drops the session.
        if (!_broken) smb2_disconnect_share(_smb2);
        smb2_destroy_context(_smb2);
    }
    // Only now can no queued request write here any more.
    free(_buffer);
}

- (NSInteger)readAtOffset:(int64_t)offset into:(uint8_t *)buffer length:(NSInteger)length error:(NSError **)error {
    if (length <= 0) return 0;
    if (_broken) {
        // A failed call's request may still be queued, aimed at _buffer.
        if (error) *error = EDSMBError(@"SMB connection lost", NULL, -EIO);
        return -1;
    }
    // libsmb2 also caps each request at the server's maximum read size.
    uint32_t count = (uint32_t)MIN(length, (NSInteger)EDSMBReadSize);
    int n = smb2_pread(_smb2, _fh, _buffer, count, (uint64_t)offset);
    if (n < 0) {
        _broken = YES;
        if (error) *error = EDSMBError(@"SMB read failed", _smb2, n);
        return -1;
    }
    n = MIN(n, (int)count);
    memcpy(buffer, _buffer, (size_t)n);
    return n;
}

- (BOOL)keepAlive {
    if (_broken) return NO;
    if (smb2_fstat(_smb2, _fh, &_stat) == 0) return YES;
    _broken = YES;
    return NO;
}

+ (EDBufferedByteSource *)byteSourceForURL:(NSURL *)url error:(NSError **)error {
    NSString *host = url.host;
    NSArray<NSString *> *segments = [url.path componentsSeparatedByString:@"/"];
    segments = [segments filteredArrayUsingPredicate:[NSPredicate predicateWithFormat:@"length > 0"]];
    if (host.length == 0 || segments.count < 2) {
        if (error) {
            *error = [NSError errorWithDomain:@"Edendale.FFmpeg" code:-EINVAL userInfo:@{
                NSLocalizedDescriptionKey: host.length == 0
                    ? @"Invalid SMB URL: missing host"
                    : @"Invalid SMB URL: missing share or file path"
            }];
        }
        return nil;
    }
    NSNumber *port = url.port;
    NSString *server = port.intValue > 0 ? [NSString stringWithFormat:@"%@:%@", host, port] : host;
    NSString *share = segments.firstObject;
    NSString *path = [[segments subarrayWithRange:NSMakeRange(1, segments.count - 1)] componentsJoinedByString:@"/"];

    // "DOMAIN;user" or "DOMAIN\user".
    NSString *user = url.user;
    NSString *domain = nil;
    for (NSString *separator in @[@";", @"\\"]) {
        NSRange range = [user rangeOfString:separator];
        if (range.location != NSNotFound) {
            domain = [user substringToIndex:range.location];
            user = [user substringFromIndex:NSMaxRange(range)];
            break;
        }
    }
    NSString *password = url.password;

    return [[EDBufferedByteSource alloc] initWithHost:host opener:^id<EDBufferedFile>(NSError **openError) {
        return [[EDSMBFile alloc] initWithServer:server share:share path:path
                                            user:user domain:domain password:password error:openError];
    }];
}

@end

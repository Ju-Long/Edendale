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
extern int smb2_close(struct smb2_context *smb2, struct smb2fh *fh);
extern int smb2_pread(struct smb2_context *smb2, struct smb2fh *fh, uint8_t *buf, uint32_t count, uint64_t offset);
extern int smb2_fstat(struct smb2_context *smb2, struct smb2fh *fh, struct smb2_stat_64 *st);
extern int smb2_echo(struct smb2_context *smb2);
extern const char *smb2_get_error(struct smb2_context *smb2);

/// Seconds a request may take before libsmb2 fails it. A stalled
/// connection is then dropped and reopened.
static const int EDSMBTimeout = 10;

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
        if (error) *error = EDSMBError(@"SMB connect failed", _smb2, rc);
        return nil;
    }
    _fh = smb2_open(_smb2, path.UTF8String, O_RDONLY);
    if (!_fh) {
        if (error) *error = EDSMBError(@"SMB open failed", _smb2, -ENOENT);
        return nil;
    }
    struct smb2_stat_64 st;
    if (smb2_fstat(_smb2, _fh, &st) == 0) _size = (int64_t)st.smb2_size;
    return self;
}

- (void)dealloc {
    if (_smb2) {
        if (_fh) smb2_close(_smb2, _fh);
        smb2_disconnect_share(_smb2);
        smb2_destroy_context(_smb2);
    }
}

- (NSInteger)readAtOffset:(int64_t)offset into:(uint8_t *)buffer length:(NSInteger)length error:(NSError **)error {
    // libsmb2 caps each request at the server's maximum read size.
    uint32_t count = (uint32_t)MIN(length, (NSInteger)(16 << 20));
    int n = smb2_pread(_smb2, _fh, buffer, count, (uint64_t)offset);
    if (n < 0) {
        if (error) *error = EDSMBError(@"SMB read failed", _smb2, n);
        return -1;
    }
    return n;
}

- (BOOL)keepAlive {
    return smb2_echo(_smb2) == 0;
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

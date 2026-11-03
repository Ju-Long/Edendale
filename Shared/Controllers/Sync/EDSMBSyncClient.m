//
//  EDSMBSyncClient.m
//  Edendale
//

#import "EDSMBSyncClient.h"
#import "EDRemoteFiles.h"
#import <fcntl.h>

// libsmb2's API, declared by hand as EDSMBFile.m does: libvlc exports the
// symbols but ships no headers. Keep these in step with the libsmb2 the
// bundled libvlc carries (6.x).

struct smb2_context;
struct smb2fh;
struct smb2dir;

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

struct smb2dirent {
    const char *name;
    struct smb2_stat_64 st;
};

#define SMB2_TYPE_FILE 0x00000000
#define SMB2_TYPE_DIRECTORY 0x00000001
#define SMB2_TYPE_LINK 0x00000002

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
extern int smb2_pwrite(struct smb2_context *smb2, struct smb2fh *fh, const uint8_t *buf, uint32_t count, uint64_t offset);
extern int smb2_fstat(struct smb2_context *smb2, struct smb2fh *fh, struct smb2_stat_64 *st);
extern struct smb2dir *smb2_opendir(struct smb2_context *smb2, const char *path);
extern struct smb2dirent *smb2_readdir(struct smb2_context *smb2, struct smb2dir *dir);
extern void smb2_closedir(struct smb2_context *smb2, struct smb2dir *dir);
extern int smb2_mkdir(struct smb2_context *smb2, const char *path);
extern int smb2_unlink(struct smb2_context *smb2, const char *path);
extern int smb2_rename(struct smb2_context *smb2, const char *oldpath, const char *newpath);
extern const char *smb2_get_error(struct smb2_context *smb2);

static const int EDSMBSyncTimeout = 15;
/// One request's most; libsmb2 also caps requests at the server's maximum.
static const uint32_t EDSMBSyncChunk = 256 * 1024;

static NSError *EDSMBSyncError(EDRemoteFileError code, struct smb2_context *smb2, int rc) {
    const char *detail = smb2 ? smb2_get_error(smb2) : NULL;
    NSMutableDictionary *info = [NSMutableDictionary dictionary];
    if (detail && detail[0]) info[NSDebugDescriptionErrorKey] = @(detail);
    info[@"rc"] = @(rc);
    return [NSError errorWithDomain:EDRemoteFileErrorDomain code:code userInfo:info];
}

@implementation EDSMBDirectoryEntry {
    NSString *_name;
    BOOL _isDirectory;
    int64_t _size;
    NSDate *_modified;
}

@synthesize name = _name, isDirectory = _isDirectory, size = _size, modified = _modified;

- (instancetype)initWithName:(NSString *)name isDirectory:(BOOL)isDirectory size:(int64_t)size modified:(NSDate *)modified {
    if ((self = [super init])) {
        _name = [name copy];
        _isDirectory = isDirectory;
        _size = size;
        _modified = modified;
    }
    return self;
}
@end

@implementation EDSMBSyncClient {
    struct smb2_context *_smb2;
    BOOL _broken;
    /// A read buffer a failed request may still point at (see EDSMBFile.m).
    uint8_t *_pendingBuffer;
    /// An smb2_close whose wait failed: libsmb2 6.1 has freed its callback
    /// data but left the request queued, so destroying the context would
    /// free it again (EDSMBFile.m never closes for this reason). The
    /// context is leaked instead; nothing runs it again.
    BOOL _leakContext;
}

@synthesize isBroken = _broken;

- (instancetype)initWithServer:(NSString *)server
                         share:(NSString *)share
                          user:(NSString *)user
                        domain:(NSString *)domain
                      password:(NSString *)password
                         error:(NSError **)error {
    if (!(self = [super init])) return nil;
    _smb2 = smb2_init_context();
    if (!_smb2) {
        if (error) *error = EDSMBSyncError(EDRemoteFileErrorIO, NULL, -ENOMEM);
        return nil;
    }
    smb2_set_timeout(_smb2, EDSMBSyncTimeout);
    if (domain.length > 0) smb2_set_domain(_smb2, domain.UTF8String);
    if (password.length > 0) smb2_set_password(_smb2, password.UTF8String);
    int rc = smb2_connect_share(_smb2, server.UTF8String, share.UTF8String, user.length > 0 ? user.UTF8String : NULL);
    if (rc < 0) {
        _broken = YES;
        if (error) *error = EDSMBSyncError(rc == -EACCES || rc == -EPERM ? EDRemoteFileErrorAuthentication : EDRemoteFileErrorConnect, _smb2, rc);
        return nil;
    }
    return self;
}

- (void)dealloc {
    if (_smb2 && !_leakContext) {
        // A healthy connection logs off; a failed one may still have a
        // request queued, so it only drops its socket (see EDSMBFile.m).
        if (!_broken) smb2_disconnect_share(_smb2);
        smb2_destroy_context(_smb2);
    }
    // Only now (or never, for a leaked context) can no queued request
    // write here any more.
    free(_pendingBuffer);
}

/// Closes `fh` on a healthy connection. A close that fails marks the
/// connection broken and its context leaked (see `_leakContext`).
- (BOOL)closeHandle:(struct smb2fh *)fh {
    if (smb2_close(_smb2, fh) < 0) {
        _broken = YES;
        _leakContext = YES;
        return NO;
    }
    return YES;
}

- (BOOL)checkUsable:(NSError **)error {
    if (!_broken) return YES;
    if (error) *error = EDSMBSyncError(EDRemoteFileErrorIO, NULL, -EIO);
    return NO;
}

- (NSError *)fail:(int)rc operation:(EDRemoteFileError)fallback {
    _broken = YES;
    EDRemoteFileError code = fallback;
    if (rc == -ENOENT) code = EDRemoteFileErrorNotFound;
    else if (rc == -EACCES || rc == -EPERM) code = EDRemoteFileErrorAuthentication;
    return EDSMBSyncError(code, _smb2, rc);
}

- (NSArray<EDSMBDirectoryEntry *> *)listDirectoryAtPath:(NSString *)path error:(NSError **)error {
    if (![self checkUsable:error]) return nil;
    struct smb2dir *dir = smb2_opendir(_smb2, path.UTF8String);
    if (!dir) {
        // A missing folder is reported without breaking the connection.
        const char *detail = smb2_get_error(_smb2);
        NSString *text = detail ? @(detail) : @"";
        BOOL missing = [text rangeOfString:@"NOT_FOUND" options:NSCaseInsensitiveSearch].location != NSNotFound
            || [text rangeOfString:@"No such" options:NSCaseInsensitiveSearch].location != NSNotFound
            || [text rangeOfString:@"OBJECT_NAME" options:NSCaseInsensitiveSearch].location != NSNotFound
            || [text rangeOfString:@"OBJECT_PATH" options:NSCaseInsensitiveSearch].location != NSNotFound;
        if (missing) {
            if (error) *error = EDSMBSyncError(EDRemoteFileErrorNotFound, _smb2, -ENOENT);
            return nil;
        }
        if (error) *error = [self fail:-EIO operation:EDRemoteFileErrorIO];
        return nil;
    }
    NSMutableArray<EDSMBDirectoryEntry *> *entries = [NSMutableArray array];
    struct smb2dirent *entry;
    while ((entry = smb2_readdir(_smb2, dir)) != NULL) {
        if (!entry->name) continue;
        NSString *name = @(entry->name);
        if ([name isEqualToString:@"."] || [name isEqualToString:@".."]) continue;
        BOOL isDirectory = entry->st.smb2_type == SMB2_TYPE_DIRECTORY;
        NSDate *modified = entry->st.smb2_mtime > 0
            ? [NSDate dateWithTimeIntervalSince1970:(NSTimeInterval)entry->st.smb2_mtime + entry->st.smb2_mtime_nsec / 1e9]
            : nil;
        [entries addObject:[[EDSMBDirectoryEntry alloc] initWithName:name
                                                         isDirectory:isDirectory
                                                                size:(int64_t)entry->st.smb2_size
                                                            modified:modified]];
    }
    smb2_closedir(_smb2, dir);
    return entries;
}

- (BOOL)createDirectoryAtPath:(NSString *)path error:(NSError **)error {
    if (![self checkUsable:error]) return NO;
    int rc = smb2_mkdir(_smb2, path.UTF8String);
    if (rc < 0 && rc != -EEXIST) {
        if (error) *error = [self fail:rc operation:EDRemoteFileErrorIO];
        return NO;
    }
    return YES;
}

- (NSData *)readFileAtPath:(NSString *)path limit:(NSUInteger)limit error:(NSError **)error {
    if (![self checkUsable:error]) return nil;
    struct smb2fh *fh = smb2_open(_smb2, path.UTF8String, O_RDONLY);
    if (!fh) {
        if (error) *error = EDSMBSyncError(EDRemoteFileErrorNotFound, _smb2, -ENOENT);
        return nil;
    }
    struct smb2_stat_64 st;
    if (smb2_fstat(_smb2, fh, &st) < 0) {
        if (error) *error = [self fail:-EIO operation:EDRemoteFileErrorIO];
        return nil;
    }
    if (st.smb2_size > limit) {
        [self closeHandle:fh];
        if (error) *error = EDSMBSyncError(EDRemoteFileErrorIO, NULL, -EFBIG);
        return nil;
    }
    NSMutableData *data = [NSMutableData dataWithCapacity:(NSUInteger)st.smb2_size];
    uint8_t *buffer = malloc(EDSMBSyncChunk);
    if (!buffer) {
        [self closeHandle:fh];
        if (error) *error = EDSMBSyncError(EDRemoteFileErrorIO, NULL, -ENOMEM);
        return nil;
    }
    uint64_t offset = 0;
    while (offset < st.smb2_size) {
        uint32_t count = (uint32_t)MIN((uint64_t)EDSMBSyncChunk, st.smb2_size - offset);
        int n = smb2_pread(_smb2, fh, buffer, count, offset);
        if (n < 0) {
            // The failed request may still point at `buffer`: it is freed
            // only after the context is destroyed, in dealloc's wake.
            NSError *failure = [self fail:n operation:EDRemoteFileErrorIO];
            _pendingBuffer = buffer;
            if (error) *error = failure;
            return nil;
        }
        if (n == 0) break;
        [data appendBytes:buffer length:(NSUInteger)n];
        offset += (uint64_t)n;
    }
    free(buffer);
    // The data is read; a failed close only retires the connection.
    [self closeHandle:fh];
    return data;
}

- (BOOL)writeData:(NSData *)data toPath:(NSString *)path error:(NSError **)error {
    if (![self checkUsable:error]) return NO;
    struct smb2fh *fh = smb2_open(_smb2, path.UTF8String, O_WRONLY | O_CREAT | O_TRUNC);
    if (!fh) {
        if (error) *error = [self fail:-EIO operation:EDRemoteFileErrorIO];
        return NO;
    }
    const uint8_t *bytes = data.bytes;
    uint64_t offset = 0;
    uint64_t total = data.length;
    while (offset < total) {
        uint32_t count = (uint32_t)MIN((uint64_t)EDSMBSyncChunk, total - offset);
        int n = smb2_pwrite(_smb2, fh, bytes + offset, count, offset);
        if (n < 0) {
            if (error) *error = [self fail:n operation:EDRemoteFileErrorIO];
            return NO;
        }
        if (n == 0) {
            if (error) *error = [self fail:-EIO operation:EDRemoteFileErrorIO];
            return NO;
        }
        offset += (uint64_t)n;
    }
    // Closed before the rename: a server may refuse to rename a file
    // that is still open.
    if (![self closeHandle:fh]) {
        if (error) *error = EDSMBSyncError(EDRemoteFileErrorIO, _smb2, -EIO);
        return NO;
    }
    return YES;
}

- (BOOL)removeItemAtPath:(NSString *)path error:(NSError **)error {
    if (![self checkUsable:error]) return NO;
    int rc = smb2_unlink(_smb2, path.UTF8String);
    if (rc < 0 && rc != -ENOENT) {
        if (error) *error = [self fail:rc operation:EDRemoteFileErrorIO];
        return NO;
    }
    return YES;
}

- (BOOL)renameItemAtPath:(NSString *)path toPath:(NSString *)newPath error:(NSError **)error {
    if (![self checkUsable:error]) return NO;
    int rc = smb2_rename(_smb2, path.UTF8String, newPath.UTF8String);
    if (rc < 0) {
        if (error) *error = [self fail:rc operation:EDRemoteFileErrorIO];
        return NO;
    }
    return YES;
}

@end

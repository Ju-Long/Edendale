//
//  EDRemoteFiles.m
//  Edendale
//
//  libnfs comes from the bundled libvlc.a (SwiftVLC's xcframework), which
//  exports its API on every Apple platform but ships no headers, so the few
//  functions used here are declared by hand, as FFmpegReader.m does for
//  libsmb2. The read signature is libnfs 6's (LIBNFS_API_V2: buffer, count,
//  offset); keep these in step with the libvlc version SwiftVLC bundles.
//

#import "EDRemoteFiles.h"
#import <fcntl.h>

NSErrorDomain const EDRemoteFileErrorDomain = @"Edendale.RemoteFile";

static NSError *EDRemoteError(EDRemoteFileError code, NSString *detail) {
    return [NSError errorWithDomain:EDRemoteFileErrorDomain code:code userInfo:detail.length > 0 ? @{
        NSDebugDescriptionErrorKey: detail
    } : nil];
}

#pragma mark - libnfs

struct nfs_context;
struct nfsfh;

extern struct nfs_context *nfs_init_context(void);
extern void nfs_destroy_context(struct nfs_context *nfs);
extern void nfs_set_timeout(struct nfs_context *nfs, int milliseconds);
extern int nfs_mount(struct nfs_context *nfs, const char *server, const char *exportname);
extern int nfs_umount(struct nfs_context *nfs);
extern int nfs_open(struct nfs_context *nfs, const char *path, int flags, struct nfsfh **nfsfh);
extern int nfs_close(struct nfs_context *nfs, struct nfsfh *nfsfh);
extern int nfs_pread(struct nfs_context *nfs, struct nfsfh *nfsfh, void *buffer, size_t count, uint64_t offset);
extern int nfs_lseek(struct nfs_context *nfs, struct nfsfh *nfsfh, int64_t offset, int whence, uint64_t *current_offset);
extern char *nfs_get_error(struct nfs_context *nfs);

@implementation EDNFSConnection {
    struct nfs_context *_nfs;
    struct nfsfh *_fh;
}

- (instancetype)initWithHost:(NSString *)host
                    filePath:(NSString *)path
         timeoutMilliseconds:(int)timeout
                       error:(NSError **)error {
    if ((self = [super init])) {
        NSMutableArray<NSString *> *components = [NSMutableArray array];
        for (NSString *component in [path componentsSeparatedByString:@"/"]) {
            if (component.length > 0) [components addObject:component];
        }
        if (components.count < 2) {
            if (error) *error = EDRemoteError(EDRemoteFileErrorNotFound, @"No export in path");
            return nil;
        }
        NSError *lastError = EDRemoteError(EDRemoteFileErrorMount, nil);
        // The URL doesn't say where the export ends: try /a, then /a/b, …
        // so the first mount that works is the export itself.
        for (NSUInteger split = 1; split < components.count; split++) {
            NSString *export = [@"/" stringByAppendingString:
                [[components subarrayWithRange:NSMakeRange(0, split)] componentsJoinedByString:@"/"]];
            NSString *relative = [@"/" stringByAppendingString:
                [[components subarrayWithRange:NSMakeRange(split, components.count - split)] componentsJoinedByString:@"/"]];

            struct nfs_context *nfs = nfs_init_context();
            if (!nfs) {
                if (error) *error = EDRemoteError(EDRemoteFileErrorIO, @"nfs_init_context failed");
                return nil;
            }
            nfs_set_timeout(nfs, timeout);
            if (nfs_mount(nfs, host.UTF8String, export.UTF8String) != 0) {
                const char *message = nfs_get_error(nfs);
                lastError = EDRemoteError(EDRemoteFileErrorMount, message ? @(message) : nil);
                nfs_destroy_context(nfs);
                continue;
            }
            struct nfsfh *fh = NULL;
            int result = nfs_open(nfs, relative.UTF8String, O_RDONLY, &fh);
            if (result != 0 || !fh) {
                const char *message = nfs_get_error(nfs);
                lastError = EDRemoteError(result == -ENOENT ? EDRemoteFileErrorNotFound : EDRemoteFileErrorIO,
                                          message ? @(message) : nil);
                nfs_umount(nfs);
                nfs_destroy_context(nfs);
                // A deeper directory may be a separate export.
                continue;
            }
            uint64_t end = 0;
            if (nfs_lseek(nfs, fh, 0, SEEK_END, &end) == 0) {
                _size = (int64_t)end;
            } else {
                _size = -1;
            }
            _nfs = nfs;
            _fh = fh;
            return self;
        }
        if (error) *error = lastError;
        return nil;
    }
    return self;
}

- (void)dealloc {
    if (_nfs) {
        if (_fh) nfs_close(_nfs, _fh);
        nfs_umount(_nfs);
        nfs_destroy_context(_nfs);
    }
}

- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
                    error:(NSError **)error {
    if (!_nfs || !_fh || length <= 0) return 0;
    NSInteger total = 0;
    while (total < length) {
        // Each call reads at most the server's negotiated read size.
        int count = nfs_pread(_nfs, _fh, buffer + total, (size_t)(length - total), (uint64_t)(offset + total));
        if (count < 0) {
            if (total > 0) break;
            const char *message = nfs_get_error(_nfs);
            if (error) *error = EDRemoteError(EDRemoteFileErrorIO, message ? @(message) : nil);
            return -1;
        }
        if (count == 0) break;
        total += count;
    }
    return total;
}

@end

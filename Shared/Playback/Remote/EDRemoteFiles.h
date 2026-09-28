//
//  EDRemoteFiles.h
//  Edendale
//
//  A thin blocking wrapper over the libnfs API that the bundled libvlc
//  already exports, for NFS sources. It only moves bytes and reports errors
//  by code; NFSConnector and RemoteFileByteSource (Swift) own the policy and
//  messages. SFTP runs on SwiftNIO SSH instead (SFTPConnection): the libssh2
//  inside libvlc can't negotiate with current OpenSSH servers.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

extern NSErrorDomain const EDRemoteFileErrorDomain;

typedef NS_ERROR_ENUM(EDRemoteFileErrorDomain, EDRemoteFileError) {
    /// The host couldn't be resolved or reached.
    EDRemoteFileErrorConnect = 1,
    /// The SSH handshake failed.
    EDRemoteFileErrorHandshake = 2,
    /// The server refused the login.
    EDRemoteFileErrorAuthentication = 3,
    /// No NFS export on the server contains the path.
    EDRemoteFileErrorMount = 4,
    EDRemoteFileErrorNotFound = 5,
    EDRemoteFileErrorIO = 6,
    EDRemoteFileErrorCancelled = 7,
} NS_SWIFT_NAME(RemoteFileError);

/// One open file on an NFS export (NFSv3, AUTH_SYS). Not thread-safe: use
/// it from one thread at a time.
NS_SWIFT_NAME(NFSConnection)
@interface EDNFSConnection : NSObject
/// Mounts the export that contains `path` on `host` (trying each ancestor
/// directory from the top, since the URL doesn't mark where the export
/// ends) and opens the file. Blocking.
- (nullable instancetype)initWithHost:(NSString *)host
                             filePath:(NSString *)path
                  timeoutMilliseconds:(int)timeout
                                error:(NSError **)error;
- (instancetype)init NS_UNAVAILABLE;
@property (nonatomic, readonly) int64_t size;
/// Returns the byte count, 0 at the end of the file, or -1 with `error` set.
- (NSInteger)readAtOffset:(int64_t)offset
                     into:(uint8_t *)buffer
                   length:(NSInteger)length
                    error:(NSError **)error;
@end

NS_ASSUME_NONNULL_END

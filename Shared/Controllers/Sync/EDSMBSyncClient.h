//
//  EDSMBSyncClient.h
//  Edendale
//
//  Directory listing, reads, and writes on one SMB share with the libsmb2
//  that the bundled libvlc exports, for sync files (SYNC-PROTOCOL.md §4).
//  Blocking and single-threaded; SMBSyncStorage drives it off the main
//  actor. A call that fails leaves the connection unusable: the caller
//  drops the client and opens a new one.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

NS_SWIFT_NAME(SMBDirectoryEntry)
@interface EDSMBDirectoryEntry : NSObject
@property (nonatomic, readonly, copy) NSString *name;
@property (nonatomic, readonly) BOOL isDirectory;
@property (nonatomic, readonly) int64_t size;
@property (nonatomic, readonly, nullable) NSDate *modified;
@end

NS_SWIFT_NAME(SMBSyncClient)
@interface EDSMBSyncClient : NSObject
/// Connects to `share` on `server` ("host" or "host:port"). Blocking.
- (nullable instancetype)initWithServer:(NSString *)server
                                  share:(NSString *)share
                                   user:(nullable NSString *)user
                                 domain:(nullable NSString *)domain
                               password:(nullable NSString *)password
                                  error:(NSError **)error;
- (instancetype)init NS_UNAVAILABLE;

/// Paths are relative to the share, with `/` separators and no leading slash.
- (nullable NSArray<EDSMBDirectoryEntry *> *)listDirectoryAtPath:(NSString *)path error:(NSError **)error;
- (BOOL)createDirectoryAtPath:(NSString *)path error:(NSError **)error;
/// The whole file, refusing one longer than `limit` bytes.
- (nullable NSData *)readFileAtPath:(NSString *)path limit:(NSUInteger)limit error:(NSError **)error;
- (BOOL)writeData:(NSData *)data toPath:(NSString *)path error:(NSError **)error;
- (BOOL)removeItemAtPath:(NSString *)path error:(NSError **)error;
- (BOOL)renameItemAtPath:(NSString *)path toPath:(NSString *)newPath error:(NSError **)error;

/// A call failed; the connection is no longer used.
@property (nonatomic, readonly) BOOL isBroken;
@end

NS_ASSUME_NONNULL_END

//
//  EDSMBFile.h
//  Edendale
//
//  One file on an SMB share, read with the libsmb2 that the bundled libvlc
//  exports. Blocking and single-threaded: EDBufferedByteSource drives it
//  from its worker thread and reconnects by opening a new one.
//

#import <Foundation/Foundation.h>
#import "EDBufferedByteSource.h"

NS_ASSUME_NONNULL_BEGIN

NS_SWIFT_NAME(SMBFile)
@interface EDSMBFile : NSObject <EDBufferedFile>
/// Connects to `share` on `server` ("host" or "host:port") and opens `path`
/// within it. Blocking.
- (nullable instancetype)initWithServer:(NSString *)server
                                  share:(NSString *)share
                                   path:(NSString *)path
                                   user:(nullable NSString *)user
                                 domain:(nullable NSString *)domain
                               password:(nullable NSString *)password
                                  error:(NSError **)error;
- (instancetype)init NS_UNAVAILABLE;

/// A buffered, reconnecting source for an `smb://[domain;]user:password@host[:port]/share/path`
/// URL, or nil with `error` set when the URL lacks a host, share, or path.
+ (nullable EDBufferedByteSource *)byteSourceForURL:(NSURL *)url error:(NSError **)error;
@end

NS_ASSUME_NONNULL_END

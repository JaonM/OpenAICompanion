#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^OCLlamaChunkHandler)(NSString *chunkJSON);
typedef BOOL (^OCLlamaCancellationHandler)(void);

// Calls the pinned llama.cpp C API. Methods are called from one serial worker queue.
@interface OCLocalLlamaEngine : NSObject

@property (nonatomic, copy, readonly, nullable) NSString *modelPath;

- (nullable NSString *)loadModelAtPath:(NSString *)path;

// Returns nil on success, otherwise a user-visible error message.
- (nullable NSString *)generateWithRequestJSON:(NSString *)requestJSON
                                     maxTokens:(NSInteger)maxTokens
                                       onChunk:(OCLlamaChunkHandler)onChunk
                                  shouldCancel:(OCLlamaCancellationHandler)shouldCancel;

@end

NS_ASSUME_NONNULL_END

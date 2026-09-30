#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** Maps Chat Completions history to roles supported by generic GGUF chat templates. */
FOUNDATION_EXPORT NSArray<NSDictionary<NSString *, NSString *> *> *OCLlamaPromptMessages(NSArray *rawMessages);

NS_ASSUME_NONNULL_END

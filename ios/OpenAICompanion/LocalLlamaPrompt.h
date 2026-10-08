#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** Maps Chat Completions history to roles supported by generic GGUF chat templates. */
FOUNDATION_EXPORT NSArray<NSDictionary<NSString *, NSString *> *> *OCLlamaPromptMessages(NSArray *rawMessages);

/** Mirrors Qwen3's enable_thinking=false suffix after its assistant prefix. */
FOUNDATION_EXPORT NSString *OCLlamaGenerationPrompt(NSString *prompt, NSString *architecture);

NS_ASSUME_NONNULL_END

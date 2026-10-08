#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** Maps Chat Completions history to roles supported by generic GGUF chat templates. */
FOUNDATION_EXPORT NSArray<NSDictionary<NSString *, NSString *> *> *OCLlamaPromptMessages(NSArray *rawMessages);

/** Mirrors Qwen3's enable_thinking=false suffix after its assistant prefix. */
FOUNDATION_EXPORT NSString *OCLlamaGenerationPrompt(NSString *prompt, NSString *architecture);

/** Constrains task states and offered tool calls; tool-enabled chat replies use a text envelope. */
FOUNDATION_EXPORT NSString * _Nullable OCLlamaResponseGrammar(NSDictionary *request, NSArray<NSString *> *toolNames);

NS_ASSUME_NONNULL_END

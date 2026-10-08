#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** Maps Chat Completions history to roles supported by generic GGUF chat templates. */
FOUNDATION_EXPORT NSArray<NSDictionary<NSString *, NSString *> *> *OCLlamaPromptMessages(NSArray *rawMessages);

FOUNDATION_EXPORT BOOL OCLlamaUsesThinking(NSString *architecture);

/** Opens supported Qwen models' thinking block after its assistant prefix. */
FOUNDATION_EXPORT NSString *OCLlamaGenerationPrompt(NSString *prompt, NSString *architecture);

/** Splits a completed thinking block; nil means thinking did not finish. */
FOUNDATION_EXPORT NSDictionary<NSString *, NSString *> * _Nullable OCLlamaSplitThinkingResponse(NSString *output);

/** Decodes the complete prefix of a chat text envelope; never returns tool/task JSON. */
FOUNDATION_EXPORT NSString * _Nullable OCLlamaStreamingChatText(NSString *output);

/** Constrains task states and offered tool calls; tool-enabled chat replies use a text envelope. */
FOUNDATION_EXPORT NSString * _Nullable OCLlamaResponseGrammar(NSDictionary *request, NSArray<NSString *> *toolNames);

NS_ASSUME_NONNULL_END

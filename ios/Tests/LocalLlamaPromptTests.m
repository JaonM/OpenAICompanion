#import <Foundation/Foundation.h>
#import "LocalLlamaPrompt.h"

int main(void) {
    @autoreleasepool {
        NSString *prefix = @"<|im_start|>assistant\n";
        NSCAssert([OCLlamaGenerationPrompt(prefix, @"qwen3") isEqualToString:
            [prefix stringByAppendingString:@"<think>\n"]], @"Qwen3 must open thinking before generation");
        NSCAssert([OCLlamaGenerationPrompt(prefix, @"qwen2") isEqualToString:prefix], @"Other model prompts must remain unchanged");
        NSCAssert([OCLlamaGenerationPrompt(prefix, @"qwen35") hasSuffix:@"<think>\n"], @"Qwen3.5 must enable thinking");
        NSCAssert(OCLlamaUsesThinking(@"qwen35"), @"Qwen3.5 architecture is supported");
        NSDictionary *thinking = OCLlamaSplitThinkingResponse(@"先分析。\n</think>\n{\"text\":\"42\"}");
        NSCAssert([thinking[@"reasoning"] containsString:@"先分析"], @"Reasoning must remain separate");
        NSCAssert([thinking[@"text"] isEqualToString:@"{\"text\":\"42\"}"], @"Only answer may enter JSON parser");
        NSCAssert(OCLlamaSplitThinkingResponse(@"未结束的思考") == nil, @"Incomplete thinking is not an answer");
        NSCAssert([OCLlamaSplitThinkingResponse(@"</think>你好")[@"text"] isEqualToString:@"你好"], @"Empty thinking must work");
        NSCAssert([OCLlamaStreamingChatText(@" { \"text\" : \"你好") isEqualToString:@"你好"], @"Partial chat text must stream without JSON framing");
        NSCAssert([OCLlamaStreamingChatText(@"{\"text\":\"a\\") isEqualToString:@"a"], @"An incomplete escape must not leak");
        NSCAssert([OCLlamaStreamingChatText(@"{\"text\":\"a\\u4F") isEqualToString:@"a"], @"An incomplete Unicode escape must wait");
        NSCAssert([OCLlamaStreamingChatText(@"{\"text\":\"a\\uD83D") isEqualToString:@"a"], @"An incomplete surrogate pair must wait");
        NSCAssert([OCLlamaStreamingChatText(@"{\"text\":\"a\\uD83D\\uDE00") isEqualToString:@"a😀"], @"Surrogate pairs must decode together");
        NSCAssert(OCLlamaStreamingChatText(@"{\"tool_call\":{\"name\":\"x\",\"arguments\":{\"text\":\"secret") == nil, @"Tool arguments must never stream as chat text");
        NSCAssert(OCLlamaStreamingChatText(@"{\"state\":\"completed\",\"text\":\"42") == nil, @"Task state JSON must stay atomic");
        NSCAssert(OCLlamaStreamingChatText(@"{\"text\":\"\\uDE00") == nil, @"Invalid lone surrogates must not stream");
        NSString *reasoningStream = @"逐步思考😀</think>{\"text\":\"42\"}";
        NSString *priorReasoning = @"";
        for (NSUInteger n = 1; n <= reasoningStream.length; n++) {
            NSString *partial = OCLlamaStreamingReasoningText([reasoningStream substringToIndex:n]);
            NSCAssert(priorReasoning.length == 0 || [partial hasPrefix:priorReasoning], @"Reasoning deltas must be monotonic");
            NSCAssert(![partial containsString:@"</think>"] && ![partial containsString:@"text"], @"Thinking markers and answers cannot leak");
            priorReasoning = partial;
        }
        NSCAssert([priorReasoning isEqualToString:@"逐步思考😀"], @"Reasoning must end exactly before the marker");
        NSString *encodedText = @"{\"text\":\"你好\\n\\\"quoted\\\"\\\\path \\uD83D\\uDE00\"}";
        NSString *previousText = @"";
        for (NSUInteger length = 1; length <= encodedText.length; length++) {
            NSString *partial = OCLlamaStreamingChatText([encodedText substringToIndex:length]);
            if (partial != nil) {
                NSCAssert(previousText.length == 0 || [partial hasPrefix:previousText], @"Token-boundary decoding must be monotonic at %lu: <%@> -> <%@>", (unsigned long)length, previousText, partial);
                previousText = partial;
            }
        }
        NSCAssert([previousText isEqualToString:@"你好\n\"quoted\"\\path 😀"], @"Final streamed answer must exactly preserve escapes and Unicode");
        NSDictionary *task = @{@"response_format": @{@"type": @"json_schema", @"json_schema": @{@"name": @"device_task_result"}}};
        NSCAssert(OCLlamaResponseGrammar(@{}, @[]) == nil, @"Ordinary chat must remain unconstrained");
        NSDictionary *plan = @{@"response_format": @{@"type": @"json_schema", @"json_schema": @{
            @"name": @"proactive_plan", @"schema": @{@"type": @"object", @"properties": @{@"action": @{@"type": @"string"}}}
        }}};
        NSCAssert([OCLlamaResponseGrammar(plan, @[]) hasPrefix:@"root ::= ws (object) ws"], @"Custom JSON schemas must activate the native response constraint");
        NSString *requiredTool = OCLlamaResponseGrammar(@{@"tool_choice": @"required"}, @[@"route_task"]);
        NSCAssert([requiredTool hasPrefix:@"root ::= ws (tool-call) ws"], @"Required execution cannot be replaced by a chat acknowledgement");
        NSString *chatTools = OCLlamaResponseGrammar(@{}, @[@"delegate_to_agent"]);
        NSCAssert([chatTools containsString:@"root ::= ws (chat-result | tool-call)"], @"Routing calls and normal replies require valid JSON");
        NSString *noTools = OCLlamaResponseGrammar(task, @[]);
        NSCAssert([noTools containsString:@"input_required"], @"Task status grammar missing");
        NSCAssert(![noTools containsString:@"tool-call"], @"No unoffered tool branch");
        NSString *withTools = OCLlamaResponseGrammar(task, @[@"calendar.find"]);
        NSCAssert([withTools containsString:@"tool-call"], @"Tool use must remain available");
        NSCAssert([withTools containsString:@"calendar.find"], @"Offered tool name missing");
        NSDictionary *discovery = @{@"messages": @[
            @{@"role": @"tool", @"name": @"list_remote_agents", @"content": @"[{\"agent_id\":\"https://example.com/mac\"}]"},
        ]};
        NSString *agentsGrammar = OCLlamaResponseGrammar(discovery, @[@"delegate_to_agent", @"list_remote_agents"]);
        NSCAssert([agentsGrammar containsString:@"agent-id ::="], @"Discovered IDs must constrain delegation arguments");
        NSCAssert([agentsGrammar containsString:@"generic-call | delegate-call"], @"Other tools must stay available");
        NSCAssert([agentsGrammar containsString:@"https://example.com/mac"], @"URL literals must not contain unsupported GBNF slash escapes");
        NSDictionary *routed = @{@"messages": @[
            @{@"role": @"tool", @"name": @"list_remote_agents", @"content": @"[{\"agent_id\":\"https://example.com/old\"}]"},
            @{@"role": @"tool", @"name": @"route_task", @"content": @"{\"decision\":\"REMOTE\",\"agent_id\":\"https://example.com/new\"}"}
        ]};
        NSString *routedGrammar = OCLlamaResponseGrammar(routed, @[@"delegate_to_agent"]);
        NSCAssert([routedGrammar containsString:@"https://example.com/new"] && ![routedGrammar containsString:@"https://example.com/old"], @"Current route must override older discovered targets");
        NSString *delegateOnly = OCLlamaResponseGrammar(discovery, @[@"delegate_to_agent"]);
        NSCAssert([delegateOnly containsString:@"tool-call ::= delegate-call"], @"Delegation must not escape through generic arguments");
        NSCAssert(![delegateOnly containsString:@"generic-call ::="], @"No generic delegation bypass");
        NSArray *history = @[
            @{ @"role": @"user", @"content": @"Find my meeting" },
            @{ @"role": @"assistant", @"content": @"", @"tool_calls": @[
                @{ @"id": @"call-42", @"function": @{
                    @"name": @"calendar.find", @"arguments": @"{}",
                } },
            ] },
            @{ @"role": @"tool", @"tool_call_id": @"call-42", @"name": @"calendar.find",
               @"content": @"{\"title\":\"Planning\"}" },
        ];
        NSArray<NSDictionary<NSString *, NSString *> *> *messages = OCLlamaPromptMessages(history);
        NSCAssert(messages.count == 3, @"Expected all conversation turns");
        NSCAssert([messages[1][@"role"] isEqualToString:@"assistant"], @"Tool request remains an assistant turn");
        NSCAssert([messages[1][@"content"] containsString:@"call-42"], @"Tool call ID was lost");
        NSCAssert([messages[1][@"content"] containsString:@"calendar.find"], @"Tool name was lost");
        NSCAssert([messages[2][@"role"] isEqualToString:@"user"], @"Generic template needs a supported role");
        NSCAssert([messages[2][@"content"] containsString:@"call-42"], @"Tool result call ID was lost");
        NSCAssert([messages[2][@"content"] containsString:@"calendar.find"], @"Tool result source was lost");
        NSCAssert([messages[2][@"content"] containsString:@"untrusted data"], @"Tool output must be labeled untrusted");
        NSCAssert([messages[2][@"content"] containsString:@"Planning"], @"Tool result body was lost");
        NSArray *nativeHistory = @[
            @{ @"role": @"developer", @"content": @"Use declared tools" },
            @{ @"role": @"assistant", @"content": NSNull.null, @"tool_calls": @[
                @{ @"id": @"call-native", @"type": @"function", @"function": @{
                    @"name": @"calendar.find", @"arguments": @"{\"limit\":2,\"enabled\":true,\"filters\":[\"today\"]}",
                } },
            ] },
            @{ @"role": @"tool", @"tool_call_id": @"call-native", @"name": @"calendar.find", @"content": @"untrusted result" },
        ];
        NSArray *native = OCMLXPromptMessages(nativeHistory);
        NSCAssert(native.count == 3, @"Native history must retain all turns");
        NSCAssert([native[0][@"role"] isEqual:@"developer"], @"Native developer role changed");
        NSDictionary *nativeCall = native[1][@"tool_calls"][0];
        NSCAssert([nativeCall[@"id"] isEqual:@"call-native"], @"Native call ID lost");
        NSCAssert([native[1][@"content"] isEqual:@""], @"Null assistant content must normalize");
        NSDictionary *arguments = nativeCall[@"function"][@"arguments"];
        NSCAssert([arguments[@"limit"] isEqual:@2] && [arguments[@"enabled"] isEqual:@YES], @"Typed arguments lost");
        NSCAssert([arguments[@"filters"] isEqual:@[@"today"]], @"Nested arguments lost");
        NSCAssert([native[2][@"role"] isEqual:@"tool"] && [native[2][@"tool_call_id"] isEqual:@"call-native"], @"Native tool result association lost");
        NSCAssert([native[2][@"content"] isEqual:@"untrusted result"], @"Tool result must stay separate data");
        NSCAssert(OCMLXPromptMessages(@[@{ @"role": @"tool", @"content": @"result" }]) == nil, @"Missing tool ID must fail");
        NSCAssert(OCMLXPromptMessages(@[@{ @"role": @"user", @"content": @[] }]) == nil, @"Unsupported multimodal content must fail");
        for (NSString *invalid in @[@"{broken", @"[]"]) {
            NSCAssert(OCMLXPromptMessages(@[@{ @"role": @"assistant", @"tool_calls": @[
                @{ @"type": @"function", @"function": @{ @"name": @"calendar.find", @"arguments": invalid } }
            ] }]) == nil, @"Malformed arguments must fail");
        }
    }
    return 0;
}

#import <Foundation/Foundation.h>
#import "LocalLlamaPrompt.h"

int main(void) {
    @autoreleasepool {
        NSString *prefix = @"<|im_start|>assistant\n";
        NSCAssert([OCLlamaGenerationPrompt(prefix, @"qwen3") isEqualToString:
            [prefix stringByAppendingString:@"<think>\n\n</think>\n\n"]], @"Qwen3 must disable thinking before generation");
        NSCAssert([OCLlamaGenerationPrompt(prefix, @"qwen2") isEqualToString:prefix], @"Other model prompts must remain unchanged");
        NSDictionary *task = @{@"response_format": @{@"type": @"json_schema", @"json_schema": @{@"name": @"device_task_result"}}};
        NSCAssert(OCLlamaResponseGrammar(@{}, @[]) == nil, @"Ordinary chat must remain unconstrained");
        NSString *chatTools = OCLlamaResponseGrammar(@{}, @[@"delegate_to_agent"]);
        NSCAssert([chatTools containsString:@"root ::= ws (chat-result | tool-call)"], @"Routing calls and normal replies require valid JSON");
        NSString *noTools = OCLlamaResponseGrammar(task, @[]);
        NSCAssert([noTools containsString:@"input_required"], @"Task status grammar missing");
        NSCAssert(![noTools containsString:@"tool-call"], @"No unoffered tool branch");
        NSString *withTools = OCLlamaResponseGrammar(task, @[@"calendar.find"]);
        NSCAssert([withTools containsString:@"tool-call"], @"Tool use must remain available");
        NSCAssert([withTools containsString:@"calendar.find"], @"Offered tool name missing");
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
    }
    return 0;
}

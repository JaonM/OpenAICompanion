
let parser = Qwen35ToolCallParser(startTag: "<tool_call>", endTag: "</tool_call>")
let tools: [[String: any Sendable]] = [["type": "function", "function": [
    "name": "sum", "parameters": ["type": "object", "properties": ["a": ["type": "integer"]]]
]]]
for content in [
    "<tool_call><function=sum><parameter=a>17</parameter></function></tool_call>",
    "<tool_call>{\"name\":\"sum\",\"arguments\":{\"a\":17}}</tool_call>"
] {
    let call = parser.parse(content: content, tools: tools)
    assert(call?.function.name == "sum")
    let wire = try JSONEncoder().encode(call!.function.arguments)
    let arguments = try JSONSerialization.jsonObject(with: wire) as! [String: Any]
    assert(arguments["a"] as! Int == 17)
}
for content in [
    "<tool_call><function=unknown></function></tool_call>",
    "<tool_call><function=sum><parameter=a>17",
    "<tool_call>{broken}</tool_call>",
    "Ordinary **Markdown** answer"
] {
    assert(parser.parse(content: content, tools: tools) == nil)
}
print("SDK Qwen35 XML/JSON, typed arguments and malformed-call tests passed")
let discovery: [[String: any Sendable]] = [["type": "function", "function": ["name": "discover", "parameters": [
    "type": "object", "properties": [String: String](), "additionalProperties": false
]]]]
let emptyCall = parser.parse(content: "<tool_call>\n<function=discover>\n</function>\n</tool_call>", tools: discovery)
assert(emptyCall?.function.name == "discover" && emptyCall?.function.arguments.isEmpty == true)


let json = try JSONSerialization.jsonObject(with: Data("{\"b\":true,\"i\":2,\"f\":2.5,\"n\":null,\"a\":[false]}".utf8))
let converted = try nativeJSON(json) as! [String: any Sendable]
assert(type(of: converted["b"]!) == Bool.self)
assert(type(of: converted["i"]!) == Int64.self)
assert(type(of: converted["f"]!) == Double.self)
assert(converted["n"] is Optional<String>)
assert((converted["a"] as! [any Sendable])[0] as! Bool == false)
for (answer, previous, expected) in [
    ("", "", ""), ("42", "", "42"), ("42", "4", "2"), ("42", "42", ""),
    ("e\u{301}", "e", "\u{301}"), ("👩‍💻", "👩", "‍💻")
] {
    let suffix = try answerSuffix(answer, after: previous)
    assert(suffix == expected)
}
do {
    _ = try answerSuffix("new", after: "old")
    fatalError("Non-contiguous output must fail")
} catch {}
print("MLX JSON types and streamed Unicode suffix tests passed")

let declared: [[String: Any]] = [["type": "function", "function": ["name": "sum", "parameters": [
    "type": "object", "properties": ["a": ["type": "integer"]], "required": ["a"], "additionalProperties": false
]]]]
let tag = try requiredToolTag(declared, format: .xmlFunction)!
let format = (try JSONSerialization.jsonObject(with: Data(tag.utf8)) as! [String: Any])["format"] as! [String: Any]
assert(format["begin"] as! String == "<tool_call>\n")
let content = format["content"] as! [String: Any]
let function = (content["elements"] as! [[String: Any]])[0]
assert(function["begin"] as! String == "<function=sum>\n")
let parameters = function["content"] as! [String: Any]
assert(parameters["type"] as! String == "qwen_xml_parameter")
let schema = parameters["json_schema"] as! [String: Any]
assert(schema["additionalProperties"] as! Bool == false)
let jsonTag = try requiredToolTag(declared, format: .qwen35)!
assert(jsonTag.contains("json_schema") && !jsonTag.contains("qwen_xml_parameter"))
print("Native required-call tag and declared schema tests passed")
let emptyTool: [[String: Any]] = [["type": "function", "function": ["name": "discover", "parameters": [
    "type": "object", "properties": [String: Any](), "additionalProperties": false
]]]]
let emptyTag = try requiredToolTag(emptyTool, format: .xmlFunction)!
assert(emptyTag.contains("const_string") && !emptyTag.contains("qwen_xml_parameter"))
let routingSchemas: [[String: Any]] = [
    ["type": "function", "function": ["name": "route_task", "parameters": ["type": "object", "properties": ["resource_refs": ["type": "array", "items": ["type": "string"]]]]]],
    ["type": "function", "function": ["name": "delegate_to_agent", "parameters": ["type": "object", "properties": ["agent_id": ["type": "string"]]]]]
]
let currentMessages: [[String: Any]] = [
    ["role": "user", "content": "Delegate this new task"],
    ["role": "tool", "name": "list_execution_devices", "content": "{\"devices\":[{\"id\":\"mac\",\"resources\":[]}]}"],
    ["role": "tool", "name": "route_task", "content": "{\"decision\":\"REMOTE\",\"agent_id\":\"https://test/agents/mac/card\"}"]
]
func properties(_ tools: [[String: Any]], _ index: Int) -> [String: Any] {
    ((tools[index]["function"] as! [String: Any])["parameters"] as! [String: Any])["properties"] as! [String: Any]
}
let constrained = try nativeTools(["tools": routingSchemas, "messages": currentMessages])
assert((properties(constrained, 0)["resource_refs"] as! [String: Any])["maxItems"] as! Int == 0)
assert((properties(constrained, 1)["agent_id"] as! [String: Any])["const"] as! String == "https://test/agents/mac/card")
let stale = try nativeTools(["tools": routingSchemas, "messages": currentMessages + [["role": "user", "content": "A different task"]]])
assert((properties(stale, 0)["resource_refs"] as! [String: Any])["maxItems"] == nil)
assert((properties(stale, 1)["agent_id"] as! [String: Any])["const"] == nil)
let resourceMessages: [[String: Any]] = [
    ["role": "user", "content": "Use a data resource"],
    ["role": "tool", "name": "list_execution_devices", "content": "{\"devices\":[{\"resources\":[\"calendar:work\"]}]}"],
]
let available = try nativeTools(["tools": routingSchemas, "messages": resourceMessages])
let refs = properties(available, 0)["resource_refs"] as! [String: Any]
assert((refs["items"] as! [String: Any])["enum"] as! [String] == ["calendar:work"])
print("Fresh discovery/resource and routed-agent schema tests passed")

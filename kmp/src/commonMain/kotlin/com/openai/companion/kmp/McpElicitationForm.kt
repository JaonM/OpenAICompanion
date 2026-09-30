package com.openai.companion.kmp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The flat, primitive subset used by MCP form-mode elicitation. */
data class McpFormField(
    val name: String,
    val title: String,
    val description: String,
    val type: String,
    val required: Boolean,
    val choices: List<String>,
    val defaultValue: String,
    val minimum: Double?,
    val maximum: Double?,
    val minLength: Int?,
    val maxLength: Int?,
)

class McpElicitationForm private constructor(val fields: List<McpFormField>) {
    fun encode(values: Map<String, String>): JsonObject = buildJsonObject {
        for (field in fields) {
            val raw = values[field.name]?.trim().orEmpty()
            if (raw.isEmpty()) {
                require(!field.required) { "${field.title} 不能为空" }
                continue
            }
            val value = when (field.type) {
                "string" -> {
                    require(field.minLength == null || raw.length >= field.minLength) { "${field.title} 太短" }
                    require(field.maxLength == null || raw.length <= field.maxLength) { "${field.title} 太长" }
                    require(field.choices.isEmpty() || raw in field.choices) { "${field.title} 不在可选范围内" }
                    JsonPrimitive(raw)
                }
                "integer" -> {
                    val number = raw.toLongOrNull() ?: throw IllegalArgumentException("${field.title} 必须是整数")
                    require(field.minimum == null || number >= field.minimum) { "${field.title} 低于最小值" }
                    require(field.maximum == null || number <= field.maximum) { "${field.title} 超过最大值" }
                    JsonPrimitive(number)
                }
                "number" -> {
                    val number = raw.toDoubleOrNull()?.takeIf(Double::isFinite)
                        ?: throw IllegalArgumentException("${field.title} 必须是有限数字")
                    require(field.minimum == null || number >= field.minimum) { "${field.title} 低于最小值" }
                    require(field.maximum == null || number <= field.maximum) { "${field.title} 超过最大值" }
                    JsonPrimitive(number)
                }
                "boolean" -> {
                    require(raw == "true" || raw == "false") { "${field.title} 必须是布尔值" }
                    JsonPrimitive(raw.toBoolean())
                }
                else -> throw IllegalArgumentException("不支持 ${field.title} 的字段类型")
            }
            put(field.name, value)
        }
    }

    companion object {
        fun parse(params: JsonObject): McpElicitationForm {
            val schema = params["requestedSchema"] as? JsonObject
                ?: throw McpProtocolException("MCP form has no requestedSchema")
            require(schema["type"]?.jsonPrimitive?.content == "object") { "MCP form schema must be an object" }
            val properties = schema["properties"] as? JsonObject
                ?: throw McpProtocolException("MCP form has no properties")
            require(properties.size <= 16) { "MCP form has too many fields" }
            val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            require(required.all { it in properties }) { "MCP form has unknown required fields" }
            val fields = properties.map { (name, rawDefinition) ->
                val definition = rawDefinition as? JsonObject
                    ?: throw McpProtocolException("MCP form field is not an object")
                val type = definition["type"]?.jsonPrimitive?.content
                    ?: throw McpProtocolException("MCP form field has no type")
                require(type in setOf("string", "integer", "number", "boolean")) {
                    "Unsupported MCP form field type: $type"
                }
                val choices = (definition["enum"] as? JsonArray)?.map { it.jsonPrimitive.content }
                    ?: (definition["oneOf"] as? JsonArray)?.map {
                        (it as? JsonObject)?.get("const")?.jsonPrimitive?.content
                            ?: throw McpProtocolException("MCP form choice has no const")
                    }.orEmpty()
                McpFormField(
                    name = name,
                    title = definition["title"]?.jsonPrimitive?.content ?: name,
                    description = definition["description"]?.jsonPrimitive?.content.orEmpty(),
                    type = type,
                    required = name in required,
                    choices = choices,
                    defaultValue = definition["default"]?.jsonPrimitive?.content.orEmpty(),
                    minimum = definition["minimum"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                    maximum = definition["maximum"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                    minLength = definition["minLength"]?.jsonPrimitive?.content?.toIntOrNull(),
                    maxLength = definition["maxLength"]?.jsonPrimitive?.content?.toIntOrNull(),
                )
            }
            return McpElicitationForm(fields)
        }
    }
}

package com.openai.companion.kmp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpElicitationFormTest {
    @Test
    fun encodesFlatFieldsAsTypedJsonAndEnforcesRequiredBounds() {
        val params = Json.parseToJsonElement("""{
            "message":"Profile",
            "requestedSchema":{
                "type":"object",
                "properties":{
                    "name":{"type":"string","title":"Name","minLength":2},
                    "age":{"type":"integer","minimum":18},
                    "rating":{"type":"number","maximum":5},
                    "enabled":{"type":"boolean"},
                    "color":{"type":"string","enum":["red","blue"]}
                },
                "required":["name","age"]
            }
        }""").jsonObject
        val form = McpElicitationForm.parse(params)
        val value = form.encode(mapOf(
            "name" to "Alice", "age" to "30", "rating" to "4.5",
            "enabled" to "true", "color" to "blue",
        ))
        assertEquals("Alice", value.getValue("name").jsonPrimitive.content)
        assertEquals("30", value.getValue("age").jsonPrimitive.content)
        assertEquals("4.5", value.getValue("rating").jsonPrimitive.content)
        assertEquals("true", value.getValue("enabled").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { form.encode(mapOf("age" to "30")) }
        assertFailsWith<IllegalArgumentException> { form.encode(mapOf("name" to "A", "age" to "30")) }
        assertFailsWith<IllegalArgumentException> { form.encode(mapOf("name" to "Alice", "age" to "17")) }
        assertFailsWith<IllegalArgumentException> { form.encode(mapOf("name" to "Alice", "age" to "30", "color" to "green")) }
    }

    @Test
    fun rejectsNestedSchemaInsteadOfPretendingItIsFlat() {
        val params = Json.parseToJsonElement("""{
            "requestedSchema":{"type":"object","properties":{"nested":{"type":"object"}}}
        }""").jsonObject
        assertFailsWith<IllegalArgumentException> { McpElicitationForm.parse(params) }
    }
}

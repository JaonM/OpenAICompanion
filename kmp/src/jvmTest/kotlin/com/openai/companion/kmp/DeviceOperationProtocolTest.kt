package com.openai.companion.kmp

import com.openai.companion.kmp.device.*
import com.openai.companion.kmp.device.calendar.CalendarCreateRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class DeviceOperationProtocolTest {
    private val key = DeviceOperationKey("device_calendar_create_event", "request-001", "{}")
    private fun journal(reply: String) = BindingsDeviceOperationJournal(object : GeneratedHarnessBindings by GeneratedHarnessBindingsAdapter() {
        override fun deviceOperation(tool: String, requestId: String, requestJson: String, claim: Boolean) = reply
    })

    @Test fun malformedWireStatesCannotAuthorizeAWrite() = runBlocking<Unit> {
        for (reply in listOf(
            """{"state":"absent","claimed":false}""",
            """{"state":"unknown","claimed":true,"operation_id":"00000000000000000000000000000001"}""",
            """{"state":"pending","claimed":true,"operation_id":"invalid"}""",
            """{"state":"succeeded","claimed":true,"result_json":"{\"status\":\"ok\"}"}""",
            """{"state":"new_future_state","claimed":true}""",
        )) {
            assertFails { journal(reply).claim(key) }
        }
        assertFails {
            journal("""{"state":"pending","claimed":true,"operation_id":"00000000000000000000000000000001"}""").lookup(key)
        }
    }

    @Test fun lookupAndClaimHaveDistinctTypedOutcomes() = runBlocking<Unit> {
        val id = "00000000000000000000000000000001"
        assertEquals(DeviceOperationRecord.Absent, journal("""{"state":"absent","claimed":false}""").lookup(key))
        assertEquals(DeviceOperationClaim.Acquired(id), journal("""{"state":"pending","claimed":true,"operation_id":"$id"}""").claim(key))
        assertEquals(DeviceOperationClaim.Existing(DeviceOperationRecord.Pending(id)),
            journal("""{"state":"pending","claimed":false,"operation_id":"$id"}""").claim(key))
    }

    @Test fun requestEncodingMatchesPersistedVersionOne() {
        val args = Json.parseToJsonElement("""{"request_id":"request-001","calendar_id":"work","calendar_title":"Work","title":"Review","start":"1970-01-01T08:00:00+08:00","end":"1970-01-01T09:00:00+08:00","time_zone":"Asia/Shanghai"}""").jsonObject
        val request = CalendarCreateRequest.parse(args)
        val existing = """{"version":1,"calendar_title":"Work","draft":{"calendarId":"work","title":"Review","startTimeMs":0,"endTimeMs":3600000,"timeZone":"Asia/Shanghai"}}"""
        assertEquals(Json.parseToJsonElement(existing), Json.parseToJsonElement(request.canonicalJson))
    }
}

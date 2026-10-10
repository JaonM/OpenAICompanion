package com.openai.companion.desktop

import kotlin.test.*

class DesktopNotificationSinkTest {
    @Test fun submissionDoesNotCountAsDelivery() {
        assertFalse(DesktopNotificationSink { _, _, _ -> 1 }.deliver("run-1", "title", "body"))
        assertTrue(DesktopNotificationSink { _, _, _ -> 2 }.deliver("run-1", "title", "body"))
    }
    @Test fun unavailableNotificationIsReportedInsteadOfMarkedDelivered() {
        assertFailsWith<IllegalStateException> {
            DesktopNotificationSink { _, _, _ -> 0 }.deliver("run-1", "title", "body")
        }
    }
}

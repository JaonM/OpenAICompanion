package com.openai.companion.kmp.device

import com.openai.companion.kmp.CalendarEventDataSource
import com.openai.companion.kmp.CalendarWriteDataSource
import com.openai.companion.kmp.device.calendar.CalendarCatalogTool
import com.openai.companion.kmp.device.calendar.CalendarCreateTool
import com.openai.companion.kmp.device.calendar.CalendarListTool

/** Every host installs identical contracts and injects its native data source. */
suspend fun createDeviceTools(
    platform: String,
    language: () -> String,
    calendar: CalendarEventDataSource,
    isForeground: suspend () -> Boolean,
    journal: DeviceOperationJournal? = null,
): DeviceToolRegistry = DeviceToolRegistry(platform, isForeground).also {
    it.register(DeviceContextTool(platform, language))
    it.register(CalendarListTool(calendar))
    if (calendar is CalendarWriteDataSource) {
        it.register(CalendarCatalogTool(calendar))
        it.register(CalendarCreateTool(calendar, requireNotNull(journal) { "Writable calendar tools require a durable journal" }, isForeground))
    }
}

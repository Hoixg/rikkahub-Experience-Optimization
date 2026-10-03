package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class SettingsBackgroundMigrationTest {
    @Test fun oldBackupDropsRetiredOptionsAndRetainsOtherDisplayChoices() {
        val root = JsonInstant.parseToJsonElement(SettingsJsonMigrator.migrate("""{"keepAwakeEnabled":true,"notificationPrivacy":true,"displaySetting":{"enableLiveUpdateNotification":true,"enableNotificationOnMessageGeneration":true,"codeBlockAutoWrap":true}}""")).jsonObject
        assertFalse("keepAwakeEnabled" in root)
        assertFalse("notificationPrivacy" in root)
        val display = root["displaySetting"]!!.jsonObject
        assertFalse("enableLiveUpdateNotification" in display)
        assertFalse("enableNotificationOnMessageGeneration" in display)
        assertTrue(display["codeBlockAutoWrap"]!!.jsonPrimitive.boolean)
    }
}

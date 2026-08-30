package tk.glucodata.alerts

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CustomAlertConfigTests {

    @Test
    fun localMinutesOfDayUsesDeviceTimeZone() {
        val timeZone = TimeZone.getTimeZone("Asia/Yekaterinburg")
        val timeMillis = GregorianCalendar(timeZone).apply {
            set(2026, 4, 24, 19, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(19 * 60 + 4, CustomAlertConfig.localMinutesOfDay(timeMillis, timeZone))
    }

    @Test
    fun activeAtUsesLocalClockTime() {
        val timeZone = TimeZone.getTimeZone("Asia/Yekaterinburg")
        val timeMillis = GregorianCalendar(timeZone).apply {
            set(2026, 4, 24, 19, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val alert = CustomAlertConfig(
            startTimeMinutes = 18 * 60,
            endTimeMinutes = 20 * 60
        )

        assertTrue(alert.isActiveAt(timeMillis, timeZone))
    }

    @Test
    fun overnightRangeWrapsAcrossMidnight() {
        val alert = CustomAlertConfig(
            startTimeMinutes = 22 * 60,
            endTimeMinutes = 7 * 60
        )

        assertTrue(alert.isActiveTime(23 * 60 + 30))
        assertTrue(alert.isActiveTime(6 * 60 + 30))
        assertFalse(alert.isActiveTime(12 * 60))
    }

    @Test
    fun allDayCustomRangeAllowsEndOfDaySentinel() {
        val alert = CustomAlertConfig(
            startTimeMinutes = 0,
            endTimeMinutes = CustomAlertConfig.MINUTES_PER_DAY
        )

        assertTrue(alert.isActiveTime(0))
        assertTrue(alert.isActiveTime(23 * 60 + 59))
    }

    @Test
    fun missingDefaultActionPreservesExistingDismissButton() {
        val restored = CustomAlertConfig.fromJson(JSONObject().put("type", "HIGH"))

        assertEquals(AlertDefaultAction.DISMISS, restored.defaultAction)
        assertEquals(15, restored.defaultSnoozeMinutes)
    }

    @Test
    fun missingDefaultActionCanUseLegacyNotificationPreferenceDuringMigration() {
        val restored = CustomAlertConfig.fromJson(
            JSONObject().put("type", "HIGH"),
            missingDefaultAction = AlertDefaultAction.SNOOZE
        )

        assertEquals(AlertDefaultAction.SNOOZE, restored.defaultAction)
    }

    @Test
    fun storedDefaultActionOverridesLegacyMigrationFallback() {
        val restored = CustomAlertConfig.fromJson(
            JSONObject()
                .put("type", "HIGH")
                .put("defaultAction", "DISMISS"),
            missingDefaultAction = AlertDefaultAction.SNOOZE
        )

        assertEquals(AlertDefaultAction.DISMISS, restored.defaultAction)
    }

    @Test
    fun defaultActionAndSnoozeDurationRoundTrip() {
        val configured = CustomAlertConfig(
            defaultAction = AlertDefaultAction.SNOOZE,
            defaultSnoozeMinutes = 35
        )

        val restored = CustomAlertConfig.fromJson(configured.toJson())

        assertEquals(AlertDefaultAction.SNOOZE, restored.defaultAction)
        assertEquals(35, restored.defaultSnoozeMinutes)
    }

    @Test
    fun unknownStoredDefaultActionFallsBackToDismiss() {
        assertEquals(AlertDefaultAction.DISMISS, AlertDefaultAction.fromStored("later"))
    }
}

package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDefaultAction
import tk.glucodata.alerts.AlertType

class AlarmActionLayoutTests {
    @Test
    fun existingConfigsKeepDismissAsThePrimaryAction() {
        val config = AlertConfig(AlertType.HIGH)

        assertEquals(AlertDefaultAction.DISMISS, config.defaultAction)
        assertEquals(AlertDefaultAction.DISMISS, alarmActionLayout(config.defaultAction).primary)
    }

    @Test
    fun selectedSnoozeBecomesPrimaryAndDismissBecomesSecondary() {
        val layout = alarmActionLayout(AlertDefaultAction.SNOOZE)

        assertEquals(AlertDefaultAction.SNOOZE, layout.primary)
        assertEquals(AlertDefaultAction.DISMISS, layout.secondary)
    }
}

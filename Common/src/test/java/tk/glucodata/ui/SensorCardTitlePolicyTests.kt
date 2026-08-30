package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorCardTitlePolicyTests {
    @Test
    fun stableSensorIdentifierWinsOverBluetoothDisplayName() {
        assertEquals(
            "SENSOR-TEST-42",
            localSensorCardTitle(
                sensorId = "SENSOR-TEST-42",
                deviceDisplayName = "AA:BB:CC:DD:EE:FF",
            ),
        )
    }

    @Test
    fun deviceDisplayNameIsOnlyFallbackForMissingIdentifier() {
        assertEquals(
            "Test sensor",
            localSensorCardTitle(sensorId = "", deviceDisplayName = "Test sensor"),
        )
    }
}

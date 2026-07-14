package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorExpiryAlertStateTests {

    // A sensor end far enough in the future that "N minutes before end" is always positive.
    private val end = 100L * 24 * 60 * 60 * 1000

    private fun min(m: Int) = m.toLong() * 60_000L

    /** Evaluate a tick "minutesBefore" minutes before [endMs]. */
    private fun SensorExpiryAlertState.fire(
        minutesBefore: Int,
        thresholds: Set<Int>,
        endMs: Long = end,
        snoozed: Boolean = false,
        active: Boolean = true
    ): Set<Int> = triggeredThresholds(
        enabled = true,
        activeNow = active,
        snoozed = snoozed,
        endTimeMs = endMs,
        nowMs = endMs - min(minutesBefore),
        thresholdsMinutes = thresholds
    )

    private val T3D = 4320
    private val T1D = 1440
    private val T6H = 360

    @Test
    fun singleThresholdFiresOnceOnEntry() {
        val s = SensorExpiryAlertState()
        val t = setOf(T1D)
        assertEquals(emptySet<Int>(), s.fire(1800, t))     // 30h before: baseline, outside window
        assertEquals(emptySet<Int>(), s.fire(1500, t))     // 25h before: still outside
        assertEquals(setOf(T1D), s.fire(1440, t))          // 24h before: crosses in -> fire
        assertEquals(emptySet<Int>(), s.fire(1200, t))     // inside: no re-fire
        assertEquals(emptySet<Int>(), s.fire(60, t))
    }

    @Test
    fun firstObservationInsideWindowDoesNotFire() {
        val s = SensorExpiryAlertState()
        assertEquals(emptySet<Int>(), s.fire(60, setOf(T1D)))   // baseline already inside
        assertEquals(emptySet<Int>(), s.fire(30, setOf(T1D)))
    }

    @Test
    fun multipleThresholdsEachFireOnceInOrder() {
        val s = SensorExpiryAlertState()
        val t = setOf(T3D, T1D, T6H)
        assertEquals(emptySet<Int>(), s.fire(5760, t))     // 4d before: baseline, outside all
        assertEquals(setOf(T3D), s.fire(4320, t))          // enter 3d
        assertEquals(emptySet<Int>(), s.fire(4000, t))     // inside 3d, no new edge
        assertEquals(setOf(T1D), s.fire(1440, t))          // enter 1d
        assertEquals(setOf(T6H), s.fire(360, t))           // enter 6h
        assertEquals(emptySet<Int>(), s.fire(30, t))
    }

    @Test
    fun baselineInsideSeveralWindowsFiresNothing() {
        val s = SensorExpiryAlertState()
        val t = setOf(T3D, T1D, T6H)
        assertEquals(emptySet<Int>(), s.fire(300, t))      // 5h before: inside all three -> baseline
        assertEquals(emptySet<Int>(), s.fire(120, t))      // still nothing
    }

    @Test
    fun newSensorRearmsAllThresholds() {
        val s = SensorExpiryAlertState()
        val t = setOf(T1D)
        s.fire(1800, t)
        assertEquals(setOf(T1D), s.fire(1440, t))
        // A different end time = new sensor: the whole sequence runs again.
        val end2 = end + 14L * 24 * 60 * 60 * 1000
        assertEquals(emptySet<Int>(), s.fire(1800, t, endMs = end2))   // baseline for new sensor
        assertEquals(setOf(T1D), s.fire(1440, t, endMs = end2))
    }

    @Test
    fun addingThresholdInsideItsWindowDoesNotFireRetroactively() {
        val s = SensorExpiryAlertState()
        s.fire(4320, setOf(T6H))                           // baseline outside the 6h window (with 6h only)
        // Now 3h before end, user adds the 1d threshold whose window opened long ago.
        val res = s.fire(180, setOf(T6H, T1D))
        assertEquals(setOf(T6H), res)                      // 6h crosses now and fires; 1d is NOT retroactive
    }

    @Test
    fun cascadeFiresOnlySmallestWhenSeveralComeDueAtOnce() {
        val s = SensorExpiryAlertState()
        val t = setOf(T3D, T1D, T6H)
        s.fire(5760, t)                                    // baseline outside all
        // Big jump (e.g. app resumed) straight to 30 min before end.
        assertEquals(setOf(T6H), s.fire(30, t))            // only the most urgent fires
        assertEquals(emptySet<Int>(), s.fire(20, t))       // rest silently marked warned
    }

    @Test
    fun snoozeDefersButDoesNotSwallowThreshold() {
        val s = SensorExpiryAlertState()
        val t = setOf(T3D, T1D)
        s.fire(5760, t)                                    // baseline outside
        assertEquals(setOf(T3D), s.fire(4320, t))          // 3d fires
        // Snoozed while the 1d window is crossed: nothing now...
        assertEquals(emptySet<Int>(), s.fire(1440, t, snoozed = true))
        // ...but once snooze ends it still fires (not swallowed).
        assertEquals(setOf(T1D), s.fire(1400, t))
    }

    @Test
    fun emptyOrDisabledFiresNothing() {
        val s = SensorExpiryAlertState()
        assertEquals(emptySet<Int>(), s.fire(60, emptySet()))
        assertEquals(
            emptySet<Int>(),
            s.triggeredThresholds(false, true, false, end, end - min(60), setOf(T1D))
        )
    }

    @Test
    fun defaultSensorExpiryConfigKeeps24hThreshold() {
        val cfg = AlertDefaults.defaultConfig(AlertType.SENSOR_EXPIRY, isMmol = false)
        assertEquals(setOf(1440), cfg.expiryWarningMinutes)
    }

    @Test
    fun sanitizeDropsUnknownThresholds() {
        assertEquals(setOf(1440, 360), sanitizeExpiryWarningMinutes(setOf(1440, 360, 999, 7)))
    }
}

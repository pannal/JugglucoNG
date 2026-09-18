package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.chart.HistoryChartModelBuilder
import tk.glucodata.chart.MainSensorOwnership

class ChartCalibrationTests {
    @Test
    fun uncalibratedTimelineResolvesEligibilityOnceAndKeepsMeasuredValues() {
        var resolutions = 0
        val calibration = cachedChartCalibration { _, _ -> resolutions++; null }
        val points = List(20_000) { index ->
            tk.glucodata.GlucosePoint((index + 1) * 60_000L, 100f + index % 50)
        }
        val model = HistoryChartModelBuilder.build(
            inputs = listOf(HistoryChartModelBuilder.SeriesInput("sensor", true, 0, 0, points)),
            ownership = MainSensorOwnership.NONE,
            calibration = calibration,
        )
        assertEquals(1, resolutions)
        for (point in points) {
            assertEquals(point.value, model.primary!!.valueAt(point.timestamp)!!, 0f)
        }
    }

    @Test
    fun absentCalibrationsAreRememberedSeparatelyForEverySensorAndLane() {
        val resolutions = mutableMapOf<Pair<Boolean, String>, Int>()
        val calibration = cachedChartCalibration { raw, sensor ->
            val key = raw to sensor
            resolutions[key] = (resolutions[key] ?: 0) + 1
            null
        }
        repeat(100) { index ->
            for (raw in listOf(false, true)) for (sensor in listOf("primary", "peer")) {
                assertNull(calibration.apply(120f, index * 60_000L, raw, sensor))
            }
        }
        assertEquals(4, resolutions.size)
        resolutions.values.forEach { assertEquals(1, it) }
    }

    @Test
    fun activeCalibrationStillAppliesAndResolvesOnce() {
        val point = tk.glucodata.data.calibration.CalPoint(x = 100.0, y = 120.0, timestamp = 60_000L)
        val tuning = tk.glucodata.data.calibration.CalibrationTuning.DEFAULT
        val series = tk.glucodata.data.calibration.SeriesCalibrator(
            listOf(point), point, tuning.algorithm, tuning, applyToPast = false
        )
        var resolutions = 0
        val calibration = cachedChartCalibration { _, _ -> resolutions++; series }
        repeat(100) {
            assertEquals(120f, calibration.apply(100f, 60_000L, false, "sensor")!!, 0f)
        }
        assertEquals(1, resolutions)
    }

    @Test
    fun nextChartBuildRechecksEligibility() {
        var resolutions = 0
        repeat(2) {
            val calibration = cachedChartCalibration { _, _ -> resolutions++; null }
            repeat(100) { calibration.apply(120f, 60_000L, false, "sensor") }
        }
        assertEquals(2, resolutions)
    }
}

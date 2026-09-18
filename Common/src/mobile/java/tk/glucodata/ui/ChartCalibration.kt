package tk.glucodata.ui

import tk.glucodata.chart.HistoryChartModelBuilder
import tk.glucodata.data.calibration.SeriesCalibrator

/** Resolves each sensor/lane once per chart build, including when no calibration applies. */
internal fun cachedChartCalibration(
    resolve: (Boolean, String) -> SeriesCalibrator?,
): HistoryChartModelBuilder.Calibration {
    val calibrators = HashMap<Pair<Boolean, String>, SeriesCalibrator?>()
    return HistoryChartModelBuilder.Calibration { base, timestamp, isRaw, sensorId ->
        val key = isRaw to sensorId
        // getOrPut treats a stored null as absent, repeating the eligibility
        // checks on every point of an uncalibrated series.
        if (!calibrators.containsKey(key)) {
            calibrators[key] = resolve(isRaw, sensorId)
        }
        calibrators[key]?.calibrate(base, timestamp)
    }
}

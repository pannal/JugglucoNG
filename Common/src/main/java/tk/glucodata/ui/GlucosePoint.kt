package tk.glucodata.ui

import tk.glucodata.GlucoseReadingSource

data class GlucosePoint(
    val value: Float,
    val time: String,
    val timestamp: Long = 0L,
    val rawValue: Float = 0f,
    val rate: Float? = null,
    val sensorSerial: String? = null,
    val source: String = GlucoseReadingSource.SENSOR
)

package tk.glucodata

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ExchangeCollapseClaimTests {
    private fun settings(minutes: Int, graphOnly: Boolean, exchangeOnly: Boolean, collapse: Boolean): Context {
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt" -> minutes
                "getBoolean" -> when (args!![0]) {
                    "dashboard_data_smoothing_graph_only" -> graphOnly
                    "dashboard_data_smoothing_exchange_outputs_only" -> exchangeOnly
                    "dashboard_data_smoothing_collapse_chunks" -> collapse
                    else -> args[1]
                }
                else -> throw AssertionError("Unexpected preference call: "+method.name)
            }
        } as SharedPreferences
        return object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
    }

    @Test fun publicPreferenceReadersAgreeForEveryScopeCombination() {
        for (minutes in listOf(0, 1, 2, 3, 4, 5, 7, 10, 13, -1)) {
            for (graph in listOf(false, true)) for (exchange in listOf(false, true)) {
                for (collapse in listOf(false, true)) {
                    val context = settings(minutes, graph, exchange, collapse)
                    val valid = minutes in listOf(2, 3, 4, 5, 7, 10, 13)
                    val expectedExchange = valid && (exchange || !graph || collapse)
                    val label = "minutes=$minutes graph=$graph exchange=$exchange collapse=$collapse"
                    assertEquals(label, expectedExchange, DataSmoothing.shouldSmoothExchangeOutputs(context))
                    assertEquals(label, if (expectedExchange) minutes else 0, DataSmoothing.exchangeSmoothingMinutes(context))
                    assertEquals(label, expectedExchange && collapse, DataSmoothing.shouldCollapseExchangeOutputs(context))
                    assertEquals(label, if (valid && !graph && !exchange) minutes else 0, DataSmoothing.localSmoothingMinutes(context))
                    assertEquals(label, if (valid && !exchange) minutes else 0, DataSmoothing.graphSmoothingMinutes(context))
                }
            }
        }
    }

    @Test fun graphOnlyCollapseProducesSmoothedExchangeButRawLocalValues() {
        val context = settings(5, true, false, true)
        val points = (0..12).map { GlucosePoint(it * 60_000L, if (it == 6) 180f else 100f, 90f) }
        val exchange = DataSmoothing.smoothNativePoints(points, DataSmoothing.exchangeSmoothingMinutes(context), false)
        val local = DataSmoothing.smoothNativePoints(points, DataSmoothing.localSmoothingMinutes(context), false)
        assertTrue(exchange[6].value < points[6].value)
        assertEquals(points.map { it.value }, local.map { it.value })
        assertTrue(DataSmoothing.shouldCollapseExchangeOutputs(context))
    }

    @Test fun stableHistoryEmitsOneClosedBucketPerInterval() {
        for (window in listOf(3, 5, 7, 13)) {
            val context = settings(window, true, false, true)
            val interval = minOf(window, 5)
            val emitted = mutableListOf<Long>()
            var previous: Long? = null
            for (at in 0..30) {
                val points = (0..at).map { GlucosePoint(it * 60_000L, 100f + it, 90f + it) }
                val smoothed = DataSmoothing.smoothNativePoints(points, DataSmoothing.exchangeSmoothingMinutes(context), false)
                val collapsed = if (DataSmoothing.shouldCollapseExchangeOutputs(context)) {
                    DataSmoothing.collapsePointsForDisplay(smoothed, interval, at * 60_000L + 30_000L)
                } else smoothed
                val timestamp = collapsed.last().timestamp
                if (!DataSmoothing.shouldCollapseExchangeOutputs(context) || timestamp != previous) emitted += timestamp
                previous = timestamp
            }
            val stable = emitted.filter { it >= interval * 60_000L }
            assertEquals((2 * interval - 1..29 step interval).map { it * 60_000L }, stable)
        }
    }

    @Test fun startupFallbackIsNotAStrictBroadcastRateLimit() {
        val context = settings(5, true, false, true)
        val outputs = (0..3).map { at ->
            val points = (0..at).map { GlucosePoint(it * 60_000L, 100f, 90f) }
            DataSmoothing.collapsePointsForDisplay(points, 5, at * 60_000L + 30_000L).last().timestamp
        }
        assertEquals(listOf(0L, 60_000L, 120_000L, 180_000L), outputs)
        assertEquals(5, DataSmoothing.collapseIntervalMinutes(13))
        assertTrue(DataSmoothing.shouldCollapseExchangeOutputs(context))
    }
}


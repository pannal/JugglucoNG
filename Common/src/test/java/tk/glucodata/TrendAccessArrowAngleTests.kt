package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tk.glucodata.logic.TrendEngine

/**
 * Closes the loop from the registered provider all the way to the rendered arrow angle: the number
 * the notification rotates by, and the one a receiver rotates by, must come out of the same
 * estimator. Kept apart from TrendAccessProviderTests because TrendArrowAngle is not upstream yet,
 * so the provider tests stay compilable against ctqvva/main on their own.
 */
class TrendAccessArrowAngleTests {

    private val t0 = 1_700_000_000_000L
    private fun p(minutesAgo: Long, value: Float) = GlucosePoint(t0 - minutesAgo * 60_000L, value, 0f)

    private val turnSeries = listOf(p(20, 160f), p(15, 150f), p(10, 140f), p(5, 128f), p(0, 136f))
    private val flatSeries = listOf(p(20, 100f), p(15, 100.5f), p(10, 100f), p(5, 100.5f), p(0, 100f))

    private val engineProvider = TrendVelocityProvider { points, useRaw, isMmol ->
        TrendEngine.calculateTrend(points, useRaw, isMmol).velocity
    }

    private fun angleVia(provider: TrendVelocityProvider?, points: List<GlucosePoint>): Float =
        TrendArrowAngle.rotationDegrees(TrendAccess.resolve(provider, points, false, false).velocity)

    @Test
    fun providerPathRendersTheEngineAngle() {
        val engineAngle =
            TrendArrowAngle.rotationDegrees(TrendEngine.calculateTrend(turnSeries, false, false).velocity)
        assertEquals(engineAngle, angleVia(engineProvider, turnSeries), 0.0001f)
    }

    @Test
    fun theFallbackWouldPointTheOtherWay() {
        // The regression falls, the last pair rises: the rendered arrows are on opposite sides of
        // flat. This is what a receiver showed while the app's own arrow disagreed.
        val provider = angleVia(engineProvider, turnSeries)
        val fallback = angleVia(null, turnSeries)
        assertNotEquals(provider, fallback, 1f)
        org.junit.Assert.assertTrue("provider angle should point down, was $provider", provider > 0f)
        org.junit.Assert.assertTrue("fallback angle should point up, was $fallback", fallback < 0f)
    }

    @Test
    fun flatDriftRendersFlatThroughTheProvider() {
        assertEquals(0f, angleVia(engineProvider, flatSeries), 0.0001f)
    }
}

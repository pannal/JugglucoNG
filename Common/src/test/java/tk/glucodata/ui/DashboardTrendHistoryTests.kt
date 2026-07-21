package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngine

/**
 * The dashboard row arrows regress over [buildTrendHistory], not over the 10 readings
 * the card actually draws.
 *
 * Handing [ReadingRow] the displayed list starved TrendEngine's 20 minute window to
 * ~10 minutes at a one-a-minute cadence, so a reading whose 20 minute trend was falling
 * drew a rising arrow on the dashboard while the same reading drew a falling one in
 * history (whole day section) and the hero drew falling too.
 */
class DashboardTrendHistoryTests {

    private val nowMillis = 1_700_000_000_000L

    /**
     * Oldest-first, one reading a minute — the shape [buildDisplayReadings] consumes.
     * Mirrors the reported trace: over 20 minutes the reading falls, but its final 10
     * minutes rise off the bottom, so the two windows genuinely disagree on direction
     * (-1.49 vs +1.2 mg/dL/min — a SingleDown arrow against a SingleUp one).
     *
     * Every minute-to-minute step stays well under 20 mg/dL so TrendEngine's
     * calibration-artifact guard does not truncate the window; the fixture has to
     * exercise the window length, not that guard.
     */
    private fun fallingWithRecentRise(): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        for (minutesAgo in 20 downTo 10) {
            // 20..10 minutes back: falling 4 mg/dL a minute, 140 -> 100.
            points.add(point(minutesAgo, 140f - (20 - minutesAgo) * 4f))
        }
        for (minutesAgo in 9 downTo 0) {
            // last 10 minutes: rising 1.2 a minute off the bottom, 100 -> 110.8.
            points.add(point(minutesAgo, 100f + (9 - minutesAgo) * 1.2f))
        }
        return points
    }

    private fun point(minutesAgo: Int, value: Float) =
        GlucosePoint(value = value, time = "", timestamp = nowMillis - minutesAgo * 60_000L)

    @Test
    fun trendHistoryCoversTheFullTrendEngineWindow() {
        val history = buildTrendHistory(fallingWithRecentRise())
        val spanMinutes = (history.first().timestamp - history.last().timestamp) / 60_000L
        assertTrue(
            "trend history must reach TrendEngine's 20 minute window, spanned $spanMinutes min",
            spanMinutes >= 20
        )
    }

    @Test
    fun displayedReadingsStayAnExactPrefixOfTrendHistory() {
        // ReadingRow slices with history.drop(index) using the *display* index, so the
        // rows drawn must line up with the head of the trend list or every row but the
        // first regresses over the wrong window.
        val consumerHistory = fallingWithRecentRise()
        val displayed = buildDisplayReadings(consumerHistory, limit = 10)
        val trendHistory = buildTrendHistory(consumerHistory)

        assertTrue(trendHistory.size > displayed.size)
        displayed.forEachIndexed { index, point ->
            assertEquals(
                "row $index must be the same reading in both lists",
                point.timestamp,
                trendHistory[index].timestamp
            )
        }
    }

    @Test
    fun starvedWindowAndFullWindowDisagreeOnDirection() {
        // Pins the actual defect: this is why the fix is needed at all. If these ever
        // agree the fixture stopped reproducing the bug and the guard below is vacuous.
        val consumerHistory = fallingWithRecentRise()
        val starved = velocityOf(buildDisplayReadings(consumerHistory, limit = 10))
        val full = velocityOf(buildTrendHistory(consumerHistory))

        assertTrue("10-point window should read as rising, was $starved", starved > 0.5f)
        assertTrue("20-minute window should read as falling, was $full", full < -0.5f)
    }

    @Test
    fun dashboardTopRowNowMatchesTheFullWindow() {
        val consumerHistory = fallingWithRecentRise()
        val trendHistory = buildTrendHistory(consumerHistory)
        // index 0 = newest displayed row, the one sitting under the hero.
        val topRow = velocityOf(trendHistory.drop(0))
        assertEquals(velocityOf(trendHistory), topRow, 0.0001f)
        assertTrue("top row must agree with the hero's falling arrow, was $topRow", topRow < -0.5f)
    }

    private fun velocityOf(points: List<GlucosePoint>): Float =
        TrendEngine.calculateTrend(
            points.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) },
            useRaw = false,
            isMmol = false
        ).velocity
}

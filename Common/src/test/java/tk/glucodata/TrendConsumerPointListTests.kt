package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.logic.TrendEngine
import tk.glucodata.logic.TrendEngineVelocityProvider
import tk.glucodata.ui.DisplayValues

/**
 * Every trend-arrow consumer must regress over the SAME point list. Historically
 * each surface assembled its own: the dashboard hero skipped the live
 * augmentation, and the notification/broadcast cut their Room load at the wall
 * clock while TrendEngine windows from the newest point — so whenever the newest
 * reading lagged "now" (always, by up to a full period), the lists differed by
 * one tail point. Invisible everywhere except the ±0.5 mg/dl/min flat dead zone,
 * where the nuance flipped one glyph but not the other for a full period
 * (observed live: dashboard ↗ vs notification → on an identical 134).
 *
 * DisplayTrendSource.resolveTrendPoints is the one canonical resolution now;
 * these tests pin element-wise identity (timestamps AND values, per the
 * acceptance criteria) and identical glyph decisions at the dead-zone edge.
 */
class TrendConsumerPointListTests {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L
    private val serial = "sensor-1"

    /** Newest reading is 45 s old — the wall-clock lag the old cut tripped over. */
    private val newestTs = now - 45_000L

    /**
     * 21 Room rows, 1-min apart. The interior 20 rise at exactly 0.48 mg/dl/min;
     * the oldest row (exactly 20 min before the newest) dips below the line, so
     * including or dropping it moves the regression across the 0.5 dead-zone
     * edge — the construction that reproduced the live glyph split pre-fix.
     */
    private fun roomBase(): List<GlucosePoint> {
        val points = ArrayList<GlucosePoint>()
        points.add(GlucosePoint(newestTs - 20 * minute, 116f, 0f))
        for (k in 19 downTo 0) {
            points.add(GlucosePoint(newestTs - k * minute, 133.1f - 0.48f * k, 0f))
        }
        return points
    }

    private fun snapshot(ts: Long, value: Float) = CurrentDisplaySource.Snapshot(
        timeMillis = ts,
        rate = Float.NaN,
        sensorId = serial,
        sensorGen = 0,
        index = 0,
        viewMode = 0,
        source = "test",
        autoValue = value,
        rawValue = 0f,
        sharedDisplayValue = 0f,
        sharedMgdl = 0,
        isMmol = false,
        displayValues = DisplayValues(primaryValue = value, primaryStr = "", fullFormatted = "")
    )

    @Before
    fun registerEngine() {
        // Mirrors Specific.start(): the release wiring every consumer runs under.
        TrendAccess.register(TrendEngineVelocityProvider)
    }

    // ---- The four consumers' trend lists, as production assembles them post-fix ----

    /** Dashboard hero: full Room window + snapshot (DashboardComponents.kt). */
    private fun dashboardList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?) =
        DisplayTrendSource.resolveTrendPoints(base, snap, null)

    /** Main notification: 3-h chart rows + snapshot (Notify.makearrownotification). */
    private fun notificationList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?) =
        DisplayTrendSource.resolveTrendPoints(base, snap, serial)

    /** Broadcast / alarm notification: rows since now-2×window + snapshot. */
    private fun broadcastList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?): List<GlucosePoint> {
        val startT = now - 2 * DisplayTrendSource.TREND_WINDOW_MS
        return DisplayTrendSource.resolveTrendPoints(base.filter { it.timestamp >= startT }, snap, serial)
    }

    /** GlucosePoint has no value equals — compare the fields the regression reads. */
    private fun triples(points: List<GlucosePoint>) =
        points.map { Triple(it.timestamp, it.value, it.rawValue) }

    // ---- Acceptance 1: element-wise list identity, timestamps AND values ----

    @Test
    fun allConsumersResolveTheIdenticalPointList() {
        val base = roomBase()
        val snap = snapshot(newestTs, 133.1f)

        val dashboard = triples(dashboardList(base, snap))
        val notification = triples(notificationList(base, snap))
        val broadcast = triples(broadcastList(base, snap))

        assertEquals(dashboard, notification)
        assertEquals(dashboard, broadcast)
        // The tail row exactly one window before the newest point stays in for everyone.
        assertEquals(21, dashboard.size)
        assertEquals(newestTs - 20 * minute, dashboard.first().first)
    }

    @Test
    fun listsStayIdenticalWhenRoomLagsTheLiveReading() {
        // The newest reading exists only as the live snapshot, not yet persisted.
        val base = roomBase().dropLast(1)
        val snap = snapshot(newestTs, 133.1f)

        val dashboard = triples(dashboardList(base, snap))
        val notification = triples(notificationList(base, snap))
        val broadcast = triples(broadcastList(base, snap))

        assertEquals(dashboard, notification)
        assertEquals(dashboard, broadcast)
        // The live point is appended for every consumer, dashboard included.
        assertEquals(newestTs, dashboard.last().first)
        assertEquals(133.1f, dashboard.last().second)
    }

    // ---- Acceptance 2: identical glyph decision at the dead-zone edge ----

    @Test
    fun glyphDecisionIsIdenticalAtTheFlatDeadZoneEdge() {
        val base = roomBase()
        val snap = snapshot(newestTs, 133.1f)

        // Dashboard hero (DashboardComponents.kt): canonical list into the engine.
        val dashboardResult = TrendEngine.calculateTrend(dashboardList(base, snap), useRaw = false, isMmol = false)

        // Notification (Notify.java) and broadcast (BroadcastTrendRate.java).
        val notificationVelocity =
            DisplayTrendSource.resolveArrowRate(notificationList(base, snap), snap, 0, false, Float.NaN)
        val broadcastVelocity =
            DisplayTrendSource.resolveArrowRate(broadcastList(base, snap), null, 0, false, Float.NaN)

        assertEquals(dashboardResult.velocity, notificationVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, broadcastVelocity, 1e-6f)

        // All sit on the same side of the ±0.5 dead zone: rising, not flat.
        assertTrue("expected > 0.5, was ${dashboardResult.velocity}", dashboardResult.velocity > 0.5f)
        assertEquals(TrendEngine.TrendState.FortyFiveUp, dashboardResult.state)
        assertNotEquals(0f, TrendArrowAngle.rotationDegrees(notificationVelocity))
        assertNotEquals(0f, TrendArrowAngle.rotationDegrees(broadcastVelocity))
    }

    @Test
    fun subDeadZoneDriftReadsFlatOnEveryConsumer() {
        // Interior slope 0.48 with no tail dip: inside the dead zone for everyone.
        val base = roomBase().drop(1)
        val snap = snapshot(newestTs, 133.1f)

        val dashboardResult = TrendEngine.calculateTrend(dashboardList(base, snap), useRaw = false, isMmol = false)
        val notificationVelocity =
            DisplayTrendSource.resolveArrowRate(notificationList(base, snap), snap, 0, false, Float.NaN)

        assertEquals(dashboardResult.velocity, notificationVelocity, 1e-6f)
        assertEquals(TrendEngine.TrendState.Flat, dashboardResult.state)
        assertEquals(0f, TrendArrowAngle.rotationDegrees(notificationVelocity), 0f)
    }
}

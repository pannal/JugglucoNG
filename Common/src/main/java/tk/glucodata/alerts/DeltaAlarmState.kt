package tk.glucodata.alerts

import tk.glucodata.GlucoseDelta

/**
 * GDH-style delta run-length counter behind the FALLING_FAST / RISING_FAST alarms.
 *
 * Robust, model-free "you're dropping/rising fast" detection: it counts consecutive readings whose
 * change over the configured interval (via [GlucoseDelta]) exceeds [deltaThreshold] in the alarming
 * direction, and fires once a run of [deltaCount] such readings has accumulated while the value is
 * past [deltaBorder]. No extrapolation, no insulin, no smoothing.
 *
 * The delta is measured over the same 1-or-5-minute window as NG's Δ readout, so the threshold is
 * cadence-independent: "10 mg/dl per 5 min" means the same on a 1-minute sensor and a 5-minute one.
 * The state keeps a short reading history to walk back to the sample one interval old, exactly like
 * the readout does.
 *
 * One instance per direction, fed the current display value on every evaluation. It advances only
 * on a genuinely newer reading timestamp, so scheduler ticks (which re-pass the same reading) and
 * calibration-only display changes cannot inflate the counter. It fires once per steep run and
 * re-arms only when the run breaks; repeat nagging is left to the shared snooze / retry machinery.
 */
internal class DeltaAlarmState(private val falling: Boolean) {
    private companion object {
        // Sentinel for "no reading ingested yet"; real reading timestamps are positive epoch millis.
        const val NO_READING = -1L
    }

    private class Sample(val timeMs: Long, val value: Float)

    private val history = ArrayDeque<Sample>()
    private var lastReadingTimeMs = NO_READING
    private var count = 0
    private var armed = true

    fun reset() {
        history.clear()
        lastReadingTimeMs = NO_READING
        count = 0
        armed = true
    }

    /**
     * Drop the reading history so the next reading starts a fresh run without counting the jump to
     * it. Used when a recalibration shifts the displayed value without a real new sample.
     */
    fun resetBaseline() {
        history.clear()
        count = 0
        armed = true
    }

    fun shouldTrigger(
        enabled: Boolean,
        activeNow: Boolean,
        snoozed: Boolean,
        value: Float,
        readingTimeMs: Long,
        deltaThreshold: Float,
        deltaCount: Int,
        deltaBorder: Float,
        intervalMinutes: Int
    ): Boolean {
        if (!enabled || deltaThreshold <= 0f || deltaCount <= 0) {
            reset()
            return false
        }

        // Advance the run counter only on a genuinely newer, finite reading.
        if (value.isFinite() && readingTimeMs > lastReadingTimeMs) {
            advanceRun(value, readingTimeMs, deltaThreshold, intervalMinutes)
            history.addLast(Sample(readingTimeMs, value))
            lastReadingTimeMs = readingTimeMs
            pruneOld(readingTimeMs, intervalMinutes)
        }

        if (!activeNow || snoozed) {
            return false
        }

        val pastBorder = if (falling) value <= deltaBorder else value >= deltaBorder
        val triggering = armed && count >= deltaCount && value.isFinite() && pastBorder
        if (triggering) {
            armed = false
            return true
        }
        return false
    }

    private fun advanceRun(value: Float, readingTimeMs: Long, deltaThreshold: Float, intervalMinutes: Int) {
        val minGap = GlucoseDelta.minGapMillis(intervalMinutes)
        // Walk back to the newest sample old enough to span (about) one interval, like the readout.
        var prev: Sample? = null
        val iterator = history.listIterator(history.size)
        while (iterator.hasPrevious()) {
            val sample = iterator.previous()
            if (readingTimeMs - sample.timeMs >= minGap) {
                prev = sample
                break
            }
        }

        val delta = if (prev != null) {
            GlucoseDelta.delta(readingTimeMs, value, prev.timeMs, prev.value, intervalMinutes)
        } else {
            Float.NaN
        }

        if (delta.isNaN()) {
            // Not enough history yet, or the pair straddles a data gap: break the run.
            count = 0
            armed = true
            return
        }

        val steep = if (falling) delta <= -deltaThreshold else delta >= deltaThreshold
        count = if (steep) count + 1 else 0
        if (count == 0) {
            armed = true  // run broken -> eligible to fire again on the next run
        }
    }

    private fun pruneOld(readingTimeMs: Long, intervalMinutes: Int) {
        // Keep a little more than the max usable gap so the walk-back always has candidates.
        val keepMillis = GlucoseDelta.minGapMillis(intervalMinutes) + 21L * 60_000L
        while (history.isNotEmpty() && readingTimeMs - history.first().timeMs > keepMillis) {
            history.removeFirst()
        }
    }
}

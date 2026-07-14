package tk.glucodata.alerts

/**
 * Edge-triggered latch for the sensor-expiry pre-warnings, one latch per
 * configured threshold. Each threshold fires exactly once per sensor when the
 * clock first crosses into its warning window (`endTimeMs - now <= threshold`).
 *
 * Preserved semantics from the original single-threshold version, now per
 * threshold:
 *  - **Edge-triggered:** fire on window entry, not on every 15s tick.
 *  - **New sensor** (`endTimeMs` changes): rearm every threshold.
 *  - **Baseline:** the first active pass only records current window membership
 *    and fires nothing, so starting the app already inside several windows does
 *    not produce a burst of alerts.
 *  - **Newly enabled thresholds** whose window is already past are adopted like
 *    the baseline (marked warned, never fired retroactively).
 *  - **Cascade guard:** if several thresholds come due in the same tick (e.g. the
 *    app resumes deep inside multiple windows), fire only the smallest (most
 *    urgent) and silently mark the rest as warned.
 */
internal class SensorExpiryAlertState {
    private var baselineReady = false
    private var lastEndTimeMs = 0L
    private val wasInWindow = mutableMapOf<Int, Boolean>()   // threshold -> inside window last tick
    private val alertedForEnd = mutableMapOf<Int, Long>()    // threshold -> endTime already warned for

    fun reset() {
        baselineReady = false
        lastEndTimeMs = 0L
        wasInWindow.clear()
        alertedForEnd.clear()
    }

    /**
     * @return the thresholds (in minutes) that should fire an alert right now.
     *   At most one element (cascade guard); empty means do nothing.
     */
    fun triggeredThresholds(
        enabled: Boolean,
        activeNow: Boolean,
        snoozed: Boolean,
        endTimeMs: Long,
        nowMs: Long,
        thresholdsMinutes: Set<Int>
    ): Set<Int> {
        if (!enabled || endTimeMs <= 0L || thresholdsMinutes.isEmpty()) {
            reset()
            return emptySet()
        }

        // New sensor -> new episode: rearm everything.
        if (endTimeMs != lastEndTimeMs) {
            baselineReady = false
            lastEndTimeMs = endTimeMs
            wasInWindow.clear()
            alertedForEnd.clear()
        }

        // Forget thresholds the user has removed so the maps stay bounded.
        wasInWindow.keys.retainAll(thresholdsMinutes)
        alertedForEnd.keys.retainAll(thresholdsMinutes)

        if (!activeNow || snoozed) {
            return emptySet()
        }

        fun inWindow(minutes: Int): Boolean =
            endTimeMs - nowMs <= minutes.toLong() * 60_000L

        // First active pass: adopt current membership, fire nothing.
        if (!baselineReady) {
            baselineReady = true
            for (t in thresholdsMinutes) {
                val inside = inWindow(t)
                wasInWindow[t] = inside
                if (inside) alertedForEnd[t] = endTimeMs
            }
            return emptySet()
        }

        val newlyDue = mutableListOf<Int>()
        for (t in thresholdsMinutes) {
            val inside = inWindow(t)
            val known = wasInWindow.containsKey(t)
            val prev = wasInWindow[t] ?: false
            wasInWindow[t] = inside
            if (!known) {
                // Threshold enabled mid-episode: adopt like the baseline, never
                // fire retroactively for a window that is already open.
                if (inside) alertedForEnd[t] = endTimeMs
                continue
            }
            if (inside && !prev && alertedForEnd[t] != endTimeMs) {
                newlyDue.add(t)
            }
        }

        if (newlyDue.isEmpty()) {
            return emptySet()
        }

        // Cascade guard: fire only the most urgent (smallest lead time); mark the
        // rest as warned so they never fire late.
        val fire = newlyDue.min()
        for (t in newlyDue) {
            alertedForEnd[t] = endTimeMs
        }
        return setOf(fire)
    }
}

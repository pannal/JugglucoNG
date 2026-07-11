/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */

package tk.glucodata;

import java.util.Locale;

/**
 * The "Δ" readout: measured change over the last ~5 minutes, in display
 * units. Unlike the trend arrow (an estimate over a window) this is a raw
 * measurement, an independent number to sanity-check the arrow's angle and
 * color against. Five minutes rather than one: adjacent readings repeat on
 * plain sensor noise, so a one-minute delta shows "0" next to a visibly
 * climbing chart — GDH's 5-minute-delta option exists for the same reason.
 */
public final class GlucoseDelta {
    public static final long WINDOW_MILLIS = 5L * 60L * 1000L;
    // Callers walk back to a point at least this much older than the newest.
    public static final long MIN_GAP_MILLIS = 4L * 60L * 1000L + 30_000L;
    // A pair further apart says nothing about the current change.
    private static final long MAX_GAP_MILLIS = 20L * 60L * 1000L;

    private GlucoseDelta() {
    }

    /**
     * Change over the pair's gap scaled to a 5-minute equivalent; NaN when
     * the pair is closer than half the window (extrapolating a single noisy
     * minute 5x would defeat the purpose) or further than 20 minutes.
     */
    public static float fiveMinuteDelta(long newMillis, float newValue, long prevMillis, float prevValue) {
        if (!Float.isFinite(newValue) || !Float.isFinite(prevValue))
            return Float.NaN;
        final long gap = newMillis - prevMillis;
        if (gap < WINDOW_MILLIS / 2L || gap > MAX_GAP_MILLIS)
            return Float.NaN;
        return (newValue - prevValue) * ((float) WINDOW_MILLIS / gap);
    }

    /**
     * Compact signed text: mg/dL as integer when close to one ("-1", "+2"),
     * one decimal otherwise; mmol always one decimal. Dot separator like the
     * value displays of GDH-style receivers.
     */
    public static String format(float deltaPerMinute, boolean isMmol) {
        if (!Float.isFinite(deltaPerMinute))
            return "";
        final float rounded = Math.round(deltaPerMinute * 10f) / 10f;
        final String sign = rounded > 0f ? "+" : "";
        if (!isMmol && Math.abs(rounded - Math.round(rounded)) < 0.05f)
            return sign + Math.round(rounded);
        return sign + String.format(Locale.ROOT, "%.1f", rounded);
    }
}

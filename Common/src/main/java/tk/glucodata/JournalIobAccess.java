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

import android.os.Looper;

// Bridge to the journal-based insulin/carb snapshot. The journal only exists
// in the mobile source set, so callers in src/main (JugglucoSend, Notify)
// resolve it by name — same pattern as OutboundApi. Returns null on variants
// without the journal (wear/small) and when the journal is unused/disabled.
public class JournalIobAccess {
    private static java.lang.reflect.Method snapshotMethod;
    private static boolean snapshotResolved;

    // Feeds the current journal IOB/COB to the native webserver so /pebble
    // polls (GlucoDataHandler's Juggluco IOB support) report the journal
    // instead of the unused native amounts store. Pushing NaN clears a value:
    // the webserver then falls back to its native computation.
    static void pushWatchserver(long atMillis) {
        float[] values = snapshot(atMillis);
        if (values != null && values.length >= 3) {
            float iobNext30 = values.length >= 5 ? values[3] : Float.NaN;
            float cobNext30 = values.length >= 5 ? values[4] : Float.NaN;
            Natives.setJournalIob(values[0], iobNext30, values[2], cobNext30, atMillis);
        } else if (Looper.myLooper() != Looper.getMainLooper()) {
            // Off the main thread null is authoritative (journal disabled or
            // empty); on the main thread it may just be a cold cache, so a
            // stored value is left to expire on its own.
            Natives.setJournalIob(Float.NaN, Float.NaN, Float.NaN, Float.NaN, atMillis);
        }
    }

    // [classicIob, eiob, cob] in units/grams, NaN marking "no data of that
    // kind"; null when unavailable.
    static float[] snapshot(long atMillis) {
        try {
            if (!snapshotResolved) {
                snapshotResolved = true;
                snapshotMethod = Class.forName("tk.glucodata.OutboundApiJournalSnapshot")
                        .getMethod("broadcastIobSnapshot", long.class);
            }
            if (snapshotMethod == null)
                return null;
            return (float[]) snapshotMethod.invoke(null, atMillis);
        } catch (Throwable e) {
            snapshotMethod = null;
            return null;
        }
    }
}

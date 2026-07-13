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

// Bridge to the journal-based insulin/carb snapshot. The journal only exists
// in the mobile source set, so callers in src/main (JugglucoSend, Notify)
// resolve it by name — same pattern as OutboundApi. Returns null on variants
// without the journal (wear/small) and when the journal is unused/disabled.
public class JournalIobAccess {
    private static java.lang.reflect.Method snapshotMethod;
    private static boolean snapshotResolved;

    // [classicIob, eiob, cob, iobNext30min, cobNext30min] in units/grams,
    // NaN marking "no data of that kind"; null when unavailable.
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

    // "IOB 4.2U · eIOB 3.1U · COB 24g" for the persistent notification, or
    // null when nothing requested is available. eIOB follows the journal
    // display toggle.
    static CharSequence notificationLine(android.content.SharedPreferences prefs, boolean showIob, boolean showCob) {
        float[] values = snapshot(System.currentTimeMillis());
        if (values == null || values.length < 3)
            return null;
        StringBuilder line = new StringBuilder();
        if (showIob && !Float.isNaN(values[0])) {
            line.append("IOB ").append(formatUnits(values[0])).append("U");
            if (!Float.isNaN(values[1]) && prefs.getBoolean("dashboard_journal_eiob_display_enabled", true))
                line.append(" · eIOB ").append(formatUnits(values[1])).append("U");
        }
        if (showCob && !Float.isNaN(values[2])) {
            if (line.length() > 0)
                line.append(" · ");
            line.append("COB ").append(formatUnits(values[2])).append("g");
        }
        return line.length() > 0 ? line.toString() : null;
    }

    private static String formatUnits(float units) {
        if (units % 1f < 0.05f)
            return String.valueOf(Math.round(units));
        return String.format(java.util.Locale.getDefault(), "%.1f", units);
    }
}

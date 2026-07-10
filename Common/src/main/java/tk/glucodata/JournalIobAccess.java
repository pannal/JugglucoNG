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

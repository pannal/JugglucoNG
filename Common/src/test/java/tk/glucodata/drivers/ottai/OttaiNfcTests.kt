package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Test

class OttaiNfcTests {
    @Test
    fun rawNfcAIsRecognizedAsFieldOnlyWake() {
        assertEquals(
            OttaiNfc.WakeInterface.NFC_A_FIELD_ONLY,
            OttaiNfc.classifyTechs(arrayOf("android.nfc.tech.NfcA")),
        )
    }

    @Test
    fun readableInterfacesTakePriorityOverBaseNfcA() {
        assertEquals(
            OttaiNfc.WakeInterface.MIFARE_ULTRALIGHT,
            OttaiNfc.classifyTechs(
                arrayOf(
                    "android.nfc.tech.NfcA",
                    "android.nfc.tech.MifareUltralight",
                ),
            ),
        )
        assertEquals(
            OttaiNfc.WakeInterface.NFC_V,
            OttaiNfc.classifyTechs(arrayOf("android.nfc.tech.NfcV")),
        )
    }

    @Test
    fun unrelatedNfcTechnologyIsNotAcceptedAsOttaiWake() {
        assertEquals(
            OttaiNfc.WakeInterface.UNSUPPORTED,
            OttaiNfc.classifyTechs(arrayOf("android.nfc.tech.IsoDep")),
        )
    }
}

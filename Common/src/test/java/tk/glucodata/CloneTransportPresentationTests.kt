package tk.glucodata

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class CloneTransportPresentationTests {
    @Test
    fun directIceWordingDoesNotClaimLanLocality() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) {
            it.parentFile
        }.first { File(it, "Common/src/main/res/values/strings.xml").isFile }
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "Common/src/main/res/values/strings.xml"))
            .getElementsByTagName("string")
        val values = (0 until strings.length).associate { index ->
            val node = strings.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }

        assertEquals("Direct ICE", values["clone_transport_local_ice"])
        assertEquals("Clone via direct ICE", values["clone_source_local_ice_description"])
    }

    @Test
    fun directIceRenamePreservesNativeAndStoredTransportValues() {
        assertEquals(1, CloneTransport.LOCAL_ICE.code)
        assertEquals(
            "clone_local_ice",
            GlucoseReadingSource.forCloneTransport(CloneTransport.LOCAL_ICE),
        )
    }

    @Test
    fun sensorRouteFollowsTheLiveConnectionState() {
        assertEquals(
            CloneTransport.TURN,
            CloneTransportPresentation.sensorTransport(CloneTransport.TURN),
        )
        assertEquals(null, CloneTransportPresentation.sensorTransport(CloneTransport.UNKNOWN))
        assertEquals(
            null,
            CloneTransportPresentation.sensorTransport(null),
        )
    }

    @Test
    fun liveCloneStatusNeverFallsBackToABluetoothSearchLabel() {
        assertEquals(
            R.string.clone_transport_local_ice,
            CloneTransportPresentation.statusTextRes(CloneTransport.LOCAL_ICE),
        )
        assertEquals(
            R.string.clone_transport_turn_ice,
            CloneTransportPresentation.statusTextRes(CloneTransport.TURN),
        )
        assertEquals(
            R.string.clone_transport_reconnecting,
            CloneTransportPresentation.statusTextRes(CloneTransport.UNKNOWN),
        )
        assertEquals(
            R.string.clone_transport_reconnecting,
            CloneTransportPresentation.statusTextRes(null),
        )
    }
}

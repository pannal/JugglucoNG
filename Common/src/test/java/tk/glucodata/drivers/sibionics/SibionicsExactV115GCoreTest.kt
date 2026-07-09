package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsExactV115GCoreTest {
    @Test
    fun driverEmitsOneReadingForEveryValidOneMinuteSample() {
        val algorithm = SibionicsAlgorithmContext("one-minute-cadence")
        algorithm.configure(shortCode = "", sensitivity = 1.50f)
        val outputs = replayRows().associate { row ->
            row.index to algorithm.process(
                rawMmol = row.rawMmol,
                temperatureC = row.temperatureC,
                index = row.index,
                mode = SibionicsAlgorithmMode.REPLAY,
            )
        }

        assertEquals(replayRows().size, outputs.size)
        assertTrue(outputs.values.all { it.isFinite() && it > 0f })
        assertEquals(29.0f, outputs.getValue(25), 0.0001f)
        assertEquals(13.4f, outputs.getValue(30), 0.0001f)
        assertEquals(13.3f, outputs.getValue(31), 0.0001f)
        assertEquals(6.8f, outputs.getValue(35), 0.0001f)
        assertEquals(6.7f, outputs.getValue(36), 0.0001f)
    }

    @Test
    fun driverSnapshotPreservesOneMinuteContinuation() {
        val rows = replayRows()
        val uninterrupted = SibionicsAlgorithmContext("uninterrupted")
        val restored = SibionicsAlgorithmContext("restored")
        uninterrupted.configure(shortCode = "", sensitivity = 1.50f)
        restored.configure(shortCode = "", sensitivity = 1.50f)
        val splitAt = rows.indexOfFirst { it.index == 7_500 }
        require(splitAt > 0)

        rows.take(splitAt).forEach { row ->
            uninterrupted.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
        }
        assertTrue(restored.restore(uninterrupted.snapshot()))

        rows.drop(splitAt).forEach { row ->
            val expected = uninterrupted.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
            val actual = restored.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
            assertEquals("one-minute output after restore at ${row.index}", expected, actual, 0.0001f)
        }
    }

    @Test
    fun liveOneMinuteReadingsReuseTheLatestReplayCorrection() {
        val rows = replayRows().associateBy { it.index }
        val algorithm = SibionicsAlgorithmContext("history-to-live")
        algorithm.configure(shortCode = "", sensitivity = 1.50f)

        (25..30).forEach { index ->
            val row = rows.getValue(index)
            algorithm.process(row.rawMmol, row.temperatureC, row.index, SibionicsAlgorithmMode.REPLAY)
        }
        val firstLive = rows.getValue(31)
        val secondLive = rows.getValue(32)

        assertEquals(
            13.3f,
            algorithm.process(firstLive.rawMmol, firstLive.temperatureC, firstLive.index, SibionicsAlgorithmMode.LIVE),
            0.0001f,
        )
        assertEquals(
            13.2f,
            algorithm.process(secondLive.rawMmol, secondLive.temperatureC, secondLive.index, SibionicsAlgorithmMode.LIVE),
            0.0001f,
        )
    }

    @Test
    fun generatedClipPreservesAllRecoveredContexts() {
        val traceLines = resourceLines("sibionics_exact_v115g_clip_trace.tsv")
        val sensitivityBits = traceLines.first().substringAfter("=").toLong(16).toInt()
        val clip = ExactV115GClip(Float.fromBits(sensitivityBits))
        val expected = resourceLines("sibionics_exact_v115g_clip_expected.tsv")
            .filterNot { it.startsWith("#") }
            .associate { line ->
                val fields = line.split('\t')
                fields[0].toInt() to fields.drop(1)
            }

        var call = 0
        traceLines.filterNot { it.startsWith("#") }.forEach { line ->
            val fields = line.split('\t')
            val values = fields.take(10).map(::floatFromHex)
            clip.update(
                adjusted = values[0],
                filter1 = values[1],
                filter2 = values[2],
                filter3 = values[3],
                filter4 = values[4],
                filter5 = values[5],
                tempRunningM20 = values[6],
                tempUpAverage = values[7],
                tempDownAverage = values[8],
                base = values[9],
                stageCount = fields[10].toInt(),
            )
            call++
            expected[call]?.let { hashes ->
                val snapshot = clip.snapshot()
                assertEquals("clip context at call $call", hashes[0], sha256(snapshot, 20, 20 + 0x4e4))
                assertEquals("adjustment context at call $call", hashes[1], sha256(snapshot, 20 + 0x4e4, 20 + 0x4e4 + 0x400))
                assertEquals("calibration context at call $call", hashes[2], sha256(snapshot, 20 + 0x4e4 + 0x400, snapshot.size))
            }
        }
        assertEquals(3_153, call)
        assertEquals(11, expected.size)
    }

    @Test
    fun replayMatchesPortableExactReference() {
        val core = SibionicsExactV115GCore(decodedSensitivity = 1.50f)
        val checkpoints = mapOf(
            25 to Checkpoint(0.5f, 1.242f, 0f, 0.009124f),
            30 to Checkpoint(-5.7708669f, 1.2833999f, 0f, 13.4186134f),
            35 to Checkpoint(0.5f, 1.2833999f, 1.5311528f, 6.8277693f),
            60 to Checkpoint(0.5f, 1.2833999f, 0.6705365f, 4.9701991f),
            205 to Checkpoint(0.5f, 1.2833999f, 0.3744822f, 3.4704323f),
            1_000 to Checkpoint(0.5f, 1.38f, 0.7883035f, 4.1554790f),
            5_000 to Checkpoint(0.5f, 1.38f, 1.2701225f, 3.1705663f),
            7_500 to Checkpoint(0.2101952f, 1.38f, 1.0361674f, 3.9895113f),
            10_000 to Checkpoint(0.2133346f, 1.38f, 1.7832664f, 4.7538600f),
            12_500 to Checkpoint(0.2267441f, 1.242f, 2.4879472f, 5.0958223f),
            15_785 to Checkpoint(0.2407059f, 1.242f, 2.4879472f, 6.2185178f),
        )

        var displayCount = 0
        replayRows().forEach { row ->
            val actual = core.process(row.rawMmol, row.temperatureC, row.index)
            if (row.exactMmol == null) {
                assertNull("unexpected display at index ${row.index}", actual)
            } else {
                displayCount++
                assertNotNull("missing display at index ${row.index}", actual)
                assertEquals("display at index ${row.index}", row.exactMmol, actual!!, 0.0001f)
            }
            checkpoints[row.index]?.let { expected ->
                val debug = core.debug ?: error("missing debug at index ${row.index}")
                assertClose("base at ${row.index}", expected.base, debug.base)
                assertClose("sensitivity at ${row.index}", expected.sensitivity, debug.sensitivity)
                assertClose("clip compensation at ${row.index}", expected.clipCompensation, debug.clipCompensation)
                assertClose("deconvolution at ${row.index}", expected.deconvolution, debug.deconvolution)
            }
        }
        assertEquals(3_153, displayCount)
    }

    @Test
    fun snapshotRestoresExactStateMidReplay() {
        val rows = replayRows()
        val uninterrupted = SibionicsExactV115GCore(decodedSensitivity = 1.50f)
        val restored = SibionicsExactV115GCore(decodedSensitivity = 1.50f)
        val splitAt = rows.indexOfFirst { it.index == 7_500 }
        require(splitAt > 0)

        rows.take(splitAt).forEach { row ->
            uninterrupted.process(row.rawMmol, row.temperatureC, row.index)
        }
        assertTrue(restored.restore(uninterrupted.snapshot()))

        rows.drop(splitAt).forEach { row ->
            val expected = uninterrupted.process(row.rawMmol, row.temperatureC, row.index)
            val actual = restored.process(row.rawMmol, row.temperatureC, row.index)
            if (expected == null) {
                assertNull("restored non-display index ${row.index}", actual)
            } else {
                assertNotNull("restored missing display index ${row.index}", actual)
                assertEquals("restored display index ${row.index}", expected, actual!!, 0.0001f)
            }
        }
    }

    private fun replayRows(): List<ReplayRow> =
        resourceLines("sibionics_exact_v115g_replay.csv")
            .asSequence()
            .drop(1)
            .map { line ->
                val fields = line.split(',')
                ReplayRow(
                    index = fields[0].toInt(),
                    rawMmol = fields[1].toFloat(),
                    temperatureC = fields[2].toFloat(),
                    exactMmol = fields[4].takeIf { it.isNotBlank() }?.toFloat(),
                )
            }
            .toList()

    private fun resourceLines(name: String): List<String> =
        javaClass.classLoader!!
            .getResourceAsStream(name)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: error("missing Sibionics exact fixture $name")

    private fun floatFromHex(hex: String): Float = Float.fromBits(hex.toLong(16).toInt())

    private fun sha256(bytes: ByteArray, from: Int, to: Int): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes.copyOfRange(from, to))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertClose(label: String, expected: Float, actual: Float) {
        assertTrue("$label: expected=$expected actual=$actual", abs(expected - actual) <= 0.0005f)
    }

    private data class ReplayRow(
        val index: Int,
        val rawMmol: Float,
        val temperatureC: Float,
        val exactMmol: Float?,
    )

    private data class Checkpoint(
        val base: Float,
        val sensitivity: Float,
        val clipCompensation: Float,
        val deconvolution: Float,
    )
}

package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tk.glucodata.GlucoseReadingSource

class HistoryDisplayMergeTests {
    private companion object {
        const val HOUR_MS = 60L * 60L * 1000L
        const val MINUTE_MS = 60L * 1000L
    }

    @Test
    fun mergeReadings_keepsOlderNonConflictingRowsAndPrefersCurrentSensorOnConflict() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 100f, rawValue = 95f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-old", value = 110f, rawValue = 104f),
                reading(id = 3, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-new", value = 111f, rawValue = 105f),
                reading(id = 4, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 114f)
            ),
            preferredSerial = "sensor-new"
        )

        assertEquals(listOf(1 * HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS), merged.map { it.timestamp })
        assertEquals(listOf("sensor-old", "sensor-new", "sensor-new"), merged.map { it.sensorSerial })
    }

    @Test
    fun mergeReadings_withoutPreferredSensorChoosesRicherReadingForSameTimestamp() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-a", value = 110f, rawValue = 0f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-b", value = 0f, rawValue = 108f),
                reading(id = 3, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-c", value = 111f, rawValue = 109f)
            ),
            preferredSerial = null
        )

        assertEquals(1, merged.size)
        assertEquals("sensor-c", merged.single().sensorSerial)
        assertEquals(111f, merged.single().value, 0.001f)
    }

    @Test
    fun mergeReadings_dropsOverlappingOlderSensorRangeWhenPreferredSensorHasCoverage() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 100f, rawValue = 95f),
                reading(id = 2, timestamp = 2 * HOUR_MS + 42 * MINUTE_MS, sensorSerial = "sensor-old", value = 101f, rawValue = 96f),
                reading(id = 3, timestamp = 2 * HOUR_MS + 58 * MINUTE_MS, sensorSerial = "sensor-old", value = 102f, rawValue = 97f),
                reading(id = 4, timestamp = 2 * HOUR_MS + 40 * MINUTE_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 115f),
                reading(id = 5, timestamp = 2 * HOUR_MS + 50 * MINUTE_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 116f),
                reading(id = 6, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 122f, rawValue = 117f)
            ).sortedBy { it.timestamp },
            preferredSerial = "sensor-new"
        )

        assertEquals(
            listOf(
                1 * HOUR_MS,
                2 * HOUR_MS + 40 * MINUTE_MS,
                2 * HOUR_MS + 50 * MINUTE_MS,
                3 * HOUR_MS
            ),
            merged.map { it.timestamp }
        )
        assertEquals(
            listOf("sensor-old", "sensor-new", "sensor-new", "sensor-new"),
            merged.map { it.sensorSerial }
        )
    }

    @Test
    fun mergeReadings_keepsOlderRowsAcrossLargePreferredSensorGap() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-old", value = 100f, rawValue = 95f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 115f),
                reading(id = 3, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-old", value = 101f, rawValue = 96f),
                reading(id = 4, timestamp = 4 * HOUR_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 116f)
            ).sortedBy { it.timestamp },
            preferredSerial = "sensor-new"
        )

        assertEquals(
            listOf(1 * HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS, 4 * HOUR_MS),
            merged.map { it.timestamp }
        )
        assertEquals(
            listOf("sensor-old", "sensor-new", "sensor-old", "sensor-new"),
            merged.map { it.sensorSerial }
        )
    }

    @Test
    fun mergeReadings_collapsesNativeLongAndShortAliasesInSameMinute() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 3 * HOUR_MS + 27 * MINUTE_MS, sensorSerial = "240601YL08230BFY", value = 110f, rawValue = 50f),
                reading(id = 2, timestamp = 3 * HOUR_MS + 27 * MINUTE_MS + 15_000L, sensorSerial = "1YL08230BFY", value = 111f, rawValue = 51f),
                reading(id = 3, timestamp = 3 * HOUR_MS + 28 * MINUTE_MS, sensorSerial = "1YL08230BFY", value = 112f, rawValue = 52f)
            ),
            preferredSerial = "1YL08230BFY"
        )

        assertEquals(
            listOf(3 * HOUR_MS + 27 * MINUTE_MS + 15_000L, 3 * HOUR_MS + 28 * MINUTE_MS),
            merged.map { it.timestamp }
        )
        assertEquals(listOf("1YL08230BFY", "1YL08230BFY"), merged.map { it.sensorSerial })
    }

    @Test
    fun mergeReadings_collapsesSameSensorRowsInSameMinute() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 8 * HOUR_MS + 39 * MINUTE_MS + 1_000L, sensorSerial = "F0FD4509C7C2", value = 63f, rawValue = 13f),
                reading(id = 2, timestamp = 8 * HOUR_MS + 39 * MINUTE_MS + 34_000L, sensorSerial = "F0FD4509C7C2", value = 64f, rawValue = 13f),
                reading(id = 3, timestamp = 8 * HOUR_MS + 42 * MINUTE_MS + 34_000L, sensorSerial = "F0FD4509C7C2", value = 65f, rawValue = 14f)
            ),
            preferredSerial = "F0FD4509C7C2"
        )

        assertEquals(
            listOf(
                8 * HOUR_MS + 39 * MINUTE_MS + 34_000L,
                8 * HOUR_MS + 42 * MINUTE_MS + 34_000L
            ),
            merged.map { it.timestamp }
        )
        assertEquals(listOf(64f, 65f), merged.map { it.value })
    }

    @Test
    fun mergeReadings_reusesSingleSensorRowsWhenMinuteBucketsAreUnique() {
        val readings = listOf(
            reading(id = 1, timestamp = 8 * HOUR_MS + 35 * MINUTE_MS, sensorSerial = "F0FD4509C7C2", value = 63f, rawValue = 13f),
            reading(id = 2, timestamp = 8 * HOUR_MS + 40 * MINUTE_MS, sensorSerial = "F0FD4509C7C2", value = 64f, rawValue = 13f),
            reading(id = 3, timestamp = 8 * HOUR_MS + 45 * MINUTE_MS, sensorSerial = "F0FD4509C7C2", value = 65f, rawValue = 14f)
        )

        assertSame(readings, HistoryDisplayMerge.mergeReadings(readings, preferredSerial = "F0FD4509C7C2"))
    }

    @Test
    fun mergeReadings_keepsImportedRowsAndPrefersLiveSensorOnOverlap() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 100f, rawValue = 90f),
                reading(id = 2, timestamp = 2 * HOUR_MS, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 101f, rawValue = 91f),
                reading(id = 3, timestamp = 2 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 110f),
                reading(id = 4, timestamp = 3 * HOUR_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 111f)
            ),
            preferredSerial = "sensor-new"
        )

        assertEquals(listOf(1 * HOUR_MS, 2 * HOUR_MS, 3 * HOUR_MS), merged.map { it.timestamp })
        assertEquals(
            listOf(HistoryRepository.IMPORTED_SENSOR_SERIAL, "sensor-new", "sensor-new"),
            merged.map { it.sensorSerial }
        )
    }

    @Test
    fun mergeReadings_keepsImportedRowsInsidePreferredCoverageWhenTheyFillGaps() {
        val merged = HistoryDisplayMerge.mergeReadings(
            readings = listOf(
                reading(id = 1, timestamp = 1 * HOUR_MS, sensorSerial = "sensor-new", value = 120f, rawValue = 110f),
                reading(id = 2, timestamp = 1 * HOUR_MS + 5 * MINUTE_MS, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 101f, rawValue = 91f),
                reading(id = 3, timestamp = 1 * HOUR_MS + 10 * MINUTE_MS, sensorSerial = "sensor-new", value = 121f, rawValue = 111f),
                reading(id = 4, timestamp = 1 * HOUR_MS + 10 * MINUTE_MS + 20_000L, sensorSerial = HistoryRepository.IMPORTED_SENSOR_SERIAL, value = 102f, rawValue = 92f),
                reading(id = 5, timestamp = 1 * HOUR_MS + 20 * MINUTE_MS, sensorSerial = "sensor-new", value = 122f, rawValue = 112f)
            ).sortedBy { it.timestamp },
            preferredSerial = "sensor-new"
        )

        assertEquals(
            listOf(
                1 * HOUR_MS,
                1 * HOUR_MS + 5 * MINUTE_MS,
                1 * HOUR_MS + 10 * MINUTE_MS,
                1 * HOUR_MS + 20 * MINUTE_MS
            ),
            merged.map { it.timestamp }
        )
        assertEquals(
            listOf(
                "sensor-new",
                HistoryRepository.IMPORTED_SENSOR_SERIAL,
                "sensor-new",
                "sensor-new"
            ),
            merged.map { it.sensorSerial }
        )
    }

    @Test
    fun mergeReadings_keepsFirstDeliveryWhenNightscoutBecomesPreferred() {
        val clone = reading(
            id = 20,
            timestamp = 5 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 112f,
            rawValue = 111f,
            source = GlucoseReadingSource.CLONE_TURN,
            firstStoredAt = 1_000L,
        )
        val nightscout = reading(
            id = 21,
            timestamp = 5 * HOUR_MS,
            sensorSerial = "NSF-TEST",
            value = 112f,
            rawValue = 112f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )

        val clonePreferred = HistoryDisplayMerge.mergeReadings(
            listOf(clone, nightscout),
            preferredSerial = "physical-sensor",
        )
        val nightscoutPreferred = HistoryDisplayMerge.mergeReadings(
            listOf(clone, nightscout),
            preferredSerial = "NSF-TEST",
        )

        assertEquals(clone, clonePreferred.single())
        assertEquals(clone, nightscoutPreferred.single())
        assertEquals(GlucoseReadingSource.CLONE_TURN, nightscoutPreferred.single().source)
    }

    @Test
    fun mergeReadings_selectsThePreferredCopyWhenReplicatedValuesDisagree() {
        val clone = reading(
            id = 30,
            timestamp = 6 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 112f,
            rawValue = 111f,
            source = GlucoseReadingSource.CLONE_LOCAL_ICE,
            firstStoredAt = 1_000L,
        )
        val nightscout = reading(
            id = 31,
            timestamp = 6 * HOUR_MS,
            sensorSerial = "NSF-TEST",
            value = 118f,
            rawValue = 118f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )

        val merged = HistoryDisplayMerge.mergeReadings(
            listOf(clone, nightscout),
            preferredSerial = "NSF-TEST",
        )

        assertEquals(nightscout, merged.single())
    }

    @Test
    fun mergeReadings_keepsAReplicaWinnerAcrossSmallTimestampSkew() {
        val clone = reading(
            id = 40,
            timestamp = 7 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 121f,
            rawValue = 120f,
            source = GlucoseReadingSource.CLONE_LOCAL_ICE,
            firstStoredAt = 1_000L,
        )
        val nightscout = reading(
            id = 41,
            timestamp = 7 * HOUR_MS + 20_000L,
            sensorSerial = "NSF-TEST",
            value = 122f,
            rawValue = 122f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )

        assertEquals(
            clone,
            HistoryDisplayMerge.mergeReadings(listOf(clone, nightscout), "physical-sensor").single(),
        )
        assertEquals(
            clone,
            HistoryDisplayMerge.mergeReadings(listOf(clone, nightscout), "NSF-TEST").single(),
        )
    }

    @Test
    fun mergeReadings_doesNotCollapseTwoIndependentPhysicalSensors() {
        val first = reading(
            id = 50,
            timestamp = 8 * HOUR_MS,
            sensorSerial = "physical-a",
            value = 133f,
            rawValue = 132f,
            firstStoredAt = 1_000L,
        )
        val second = reading(
            id = 51,
            timestamp = 8 * HOUR_MS,
            sensorSerial = "physical-b",
            value = 133f,
            rawValue = 132f,
            firstStoredAt = 2_000L,
        )

        assertEquals(
            first,
            HistoryDisplayMerge.mergeReadings(listOf(first, second), "physical-a").single(),
        )
        assertEquals(
            second,
            HistoryDisplayMerge.mergeReadings(listOf(first, second), "physical-b").single(),
        )
    }

    @Test
    fun mergeReadings_keepsMultiRowReplicaProvenanceUnderEitherPreference() {
        val cloneFirst = reading(
            id = 60,
            timestamp = 9 * HOUR_MS,
            sensorSerial = "physical-sensor",
            value = 140f,
            rawValue = 139f,
            source = GlucoseReadingSource.CLONE_TURN,
            firstStoredAt = 1_000L,
        )
        val nightscoutFirst = reading(
            id = 61,
            timestamp = 9 * HOUR_MS + 5 * MINUTE_MS,
            sensorSerial = "NSF-TEST",
            value = 145f,
            rawValue = 145f,
            source = GlucoseReadingSource.NIGHTSCOUT,
            firstStoredAt = 2_000L,
        )
        val copies = listOf(
            cloneFirst,
            reading(62, cloneFirst.timestamp, "NSF-TEST", 140f, 140f, GlucoseReadingSource.NIGHTSCOUT, 3_000L),
            nightscoutFirst,
            reading(63, nightscoutFirst.timestamp, "physical-sensor", 145f, 144f, GlucoseReadingSource.CLONE_TURN, 4_000L),
        ).sortedBy(HistoryReading::timestamp)

        val expected = listOf(cloneFirst, nightscoutFirst)
        assertEquals(expected, HistoryDisplayMerge.mergeReadings(copies, "physical-sensor"))
        assertEquals(expected, HistoryDisplayMerge.mergeReadings(copies, "NSF-TEST"))
    }

    private fun reading(
        id: Long,
        timestamp: Long,
        sensorSerial: String,
        value: Float,
        rawValue: Float,
        source: String = GlucoseReadingSource.SENSOR,
        firstStoredAt: Long = id,
    ) = HistoryReading(
        id = id,
        timestamp = timestamp,
        sensorSerial = sensorSerial,
        value = value,
        rawValue = rawValue,
        rate = null,
        source = source,
        firstStoredAt = firstStoredAt,
    )
}

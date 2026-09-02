package tk.glucodata.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDatabaseSafetyTests {
    private fun source(relativePath: String): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val repositoryPath = File(directory, "Common/$relativePath")
            if (repositoryPath.isFile) return repositoryPath.readText()

            val modulePath = File(directory, relativePath)
            if (modulePath.isFile) return modulePath.readText()

            directory = directory.parentFile
        }
        error("Could not locate $relativePath")
    }

    private fun historyDatabaseSource() =
        source("src/mobile/java/tk/glucodata/data/HistoryDatabase.kt")

    @Test
    fun historyDatabaseDoesNotUseDestructiveMigrationFallback() {
        assertFalse(
            "An incompatible history database must fail to open without deleting stored history",
            historyDatabaseSource().contains("fallbackToDestructiveMigration")
        )
    }

    @Test
    fun startupValidatesAnExistingDatabaseBeforeContinuing() {
        assertTrue(
            "The startup probe must force Room to validate the existing database",
            historyDatabaseSource().contains("openHelper.writableDatabase")
        )
        assertTrue(
            "MainActivity must stop before normal startup when the history database is incompatible",
            source("src/main/java/tk/glucodata/MainActivity.java")
                .contains("if (!Specific.historyDatabaseCompatible(this))")
        )
    }

    @Test
    fun provenanceAndCloneMigrationsExtendTheCurrentLocalSchema() {
        val source = historyDatabaseSource()
        assertTrue(source.contains("version = 28"))
        assertTrue(
            source.contains(
                "private val MIGRATION_23_24 = object : Migration(23, 24)"
            )
        )
        assertTrue(
            source.contains("ADD COLUMN source TEXT NOT NULL DEFAULT 'sensor'")
        )
        assertTrue(
            source.contains(
                "private val MIGRATION_24_25 = object : Migration(24, 25)"
            )
        )
        assertTrue(
            source.contains("ADD COLUMN firstStoredAt INTEGER NOT NULL DEFAULT 0")
        )
        assertTrue(
            source.contains(
                "private val MIGRATION_25_26 = object : Migration(25, 26)"
            )
        )
        assertTrue(source.contains("ADD COLUMN originSource TEXT"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS clone_journal_tombstones"))
    }

    @Test
    fun journalRecoveryIdentityMigrationIsRegisteredAndNonDestructive() {
        val source = historyDatabaseSource()

        assertTrue(source.contains("version = 28"))
        assertTrue(source.contains("Migration(26, 27)"))
        assertTrue(source.contains("ALTER TABLE journal_entries ADD COLUMN recoveryId TEXT"))
        assertTrue(source.contains("lower(hex(randomblob(16)))"))
        assertTrue(source.contains("index_journal_entries_recoveryId"))
        assertTrue(source.contains("ALTER TABLE clone_journal_tombstones ADD COLUMN recoveryId TEXT"))
        assertTrue(source.contains("MIGRATION_26_27"))
        assertFalse(source.contains("DROP TABLE journal_entries"))
    }

    @Test
    fun recoveredJournalDeletionMigrationIsRegisteredAndNonDestructive() {
        val source = historyDatabaseSource()

        assertTrue(source.contains("Migration(27, 28)"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS clone_journal_recovery_tombstones"))
        assertTrue(source.contains("PRIMARY KEY(stableBaseId)"))
        assertTrue(source.contains("index_clone_journal_recovery_tombstones_recoveryId"))
        assertTrue(source.contains("MIGRATION_27_28"))
        assertFalse(source.contains("DROP TABLE clone_journal_tombstones"))
    }
}

package tk.glucodata.data;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static tk.glucodata.data.CloneRecoveryMigrationTests.*;

public class IntegratedSchemaMigrationTests {
    private Connection fixture(String name) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var input = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(input);
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!sql.isBlank()) exec(connection, sql);
            }
        }
        return connection;
    }

    @Test public void installedV30KeepsRowsAndMatchesGeneratedRoomSchema() throws Exception {
        try (Connection expected = db(); Connection actual = fixture("local-v30-schema.sql")) {
            exec(actual, "INSERT INTO history_readings(id,timestamp,sensorSerial,value,rawValue,source,firstStoredAt) VALUES(1,60023,'sensor-a',100,101,'clone',60024)");
            exec(actual, "INSERT INTO reading_display VALUES(60000,'sensor-a',100,0,0,70000)");
            exec(actual, "INSERT INTO meals(id,label,createdAt,updatedAt) VALUES(7,'Keep meal',900,1100)");
            exec(actual, "INSERT INTO journal_entries(id,timestamp,entryType,title,source,createdAt,updatedAt,mealId,recoveryId,lvUploadedAt) VALUES(7,1000,'note','Keep note','manual',900,1100,7,'kept-recovery',1500)");
            exec(actual, "INSERT INTO hypo_episode_marks VALUES(1,2,65,'real','manual',3)");
            exec(actual, "INSERT INTO clone_recovery_imports(jobId,sha256) VALUES('job','hash')");
            var history = query(actual, "SELECT * FROM history_readings");
            var display = query(actual, "SELECT * FROM reading_display");
            var journal = query(actual, "SELECT title,mealId,recoveryId,lvUploadedAt FROM journal_entries");
            migrate(actual, 30, 37);
            assertEquals(schema(expected), schema(actual));
            assertEquals(history, query(actual, "SELECT * FROM history_readings"));
            assertEquals(display, query(actual, "SELECT * FROM reading_display"));
            assertEquals(journal, query(actual, "SELECT title,mealId,recoveryId,lvUploadedAt FROM journal_entries"));
            assertEquals("Keep meal", query(actual, "SELECT label FROM meals WHERE id=7").get(0).get(0));
            assertEquals("65.0", query(actual, "SELECT nadirMgdl FROM hypo_episode_marks").get(0).get(0));
            assertEquals("hash", query(actual, "SELECT sha256 FROM clone_recovery_imports").get(0).get(0));
        }
    }

    @Test public void upstreamV32AddsFeatureTablesWithoutChangingExistingData() throws Exception {
        try (Connection expected = db(); Connection actual = fixture("upstream-v32-schema.sql")) {
            exec(actual, "INSERT INTO journal_entries(id,timestamp,entryType,title,source,createdAt,updatedAt) VALUES(7,1000,'note','Upstream note','manual',900,1100)");
            migrate(actual, 32, 37);
            assertEquals(schema(expected), schema(actual));
            assertEquals("Upstream note", query(actual, "SELECT title FROM journal_entries WHERE id=7").get(0).get(0));
            assertNotNull(query(actual, "SELECT recoveryId FROM journal_entries WHERE id=7").get(0).get(0));
        }
    }

    @Test public void featurePreviewBridgesAreIdempotent() throws Exception {
        for (int version = 30; version <= 36; version++) {
            try (Connection expected = db(); Connection actual = db()) {
                migrate(actual, version, 37);
                migrate(actual, version, 37);
                assertEquals("Bridge from " + version, schema(expected), schema(actual));
            }
        }
    }
}

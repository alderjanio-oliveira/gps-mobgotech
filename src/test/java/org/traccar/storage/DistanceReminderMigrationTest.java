package org.traccar.storage;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DistanceReminderMigrationTest {

    @TempDir
    private Path baseline;

    @Test
    public void freshSchemaPreservesReminderAndLinksAcrossRepeatedStartup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            migrate(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO tc_users (id, name, email, attributes) "
                        + "VALUES (1, 'Operator', 'test@example.invalid', '{}')");
                statement.execute("INSERT INTO tc_devices (id, name, uniqueid, attributes) "
                        + "VALUES (1, 'Tracker', 'test-device', '{}')");
                statement.execute("INSERT INTO tc_distance_reminders "
                        + "(id, name, thresholddistance, startvalue, status, attributes) "
                        + "VALUES (1, 'Oil', 1000000, 0, 'pending', '{}')");
                statement.execute("INSERT INTO tc_user_distanceReminder VALUES (1, 1)");
                statement.execute("INSERT INTO tc_device_distanceReminder VALUES (1, 1)");
            }
            migrate(connection);
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT r.startvalue, r.thresholddistance "
                            + "FROM tc_distance_reminders r "
                            + "JOIN tc_user_distanceReminder u ON u.distancereminderid = r.id "
                            + "JOIN tc_device_distanceReminder d ON d.distancereminderid = r.id")) {
                assertTrue(result.next());
                assertEquals(0, result.getDouble(1));
                assertEquals(1000000, result.getDouble(2));
            }
        }
    }

    @Test
    public void upgradesPreviousForkWithoutLosingReminderHistory() throws Exception {
        try (var paths = Files.list(Path.of("schema"))) {
            for (Path path : paths.toList()) {
                Files.copy(path, baseline.resolve(path.getFileName()));
            }
        }
        try (var oldIndexMigration = getClass().getResourceAsStream(
                "/schema-fork-6.14.5/changelog-6.11.0.xml")) {
            Files.copy(oldIndexMigration, baseline.resolve("changelog-6.11.0.xml"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Path master = baseline.resolve("changelog-master.xml");
        Files.writeString(master, Files.readAllLines(master).stream()
                .filter(line -> !line.contains("changelog-6.15.0.xml") && !line.contains("changelog-6.17.0.xml")
                        && !line.contains("changelog-mobgo-notification-outbox.xml")
                        && !line.contains("changelog-mobgo-billing.xml")
                        && !line.contains("changelog-mobgo-health.xml")
                        && !line.contains("changelog-mobgo-command-ledger.xml"))
                .collect(Collectors.joining(System.lineSeparator())));
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            migrate(connection, baseline);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO tc_distance_reminders "
                        + "(id, name, thresholddistance, startvalue, status, traveleddistance, attributes) "
                        + "VALUES (1, 'Oil', 1000000, 15000000, 'done', 1000123, '{}')");
            }
            migrate(connection);
            migrate(connection);
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT status, startvalue, traveleddistance "
                            + "FROM tc_distance_reminders WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals("done", result.getString(1));
                assertEquals(15000000, result.getDouble(2));
                assertEquals(1000123, result.getDouble(3));
            }
        }
    }

    private void migrate(Connection connection) throws Exception {
        migrate(connection, Path.of("schema"));
    }

    private void migrate(Connection connection, Path schema) throws Exception {
        try (var resources = new DirectoryResourceAccessor(schema)) {
            var liquibase = new Liquibase("changelog-master.xml", resources, new JdbcConnection(connection));
            liquibase.update(new Contexts());
        }
    }
}

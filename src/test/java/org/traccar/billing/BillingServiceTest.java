package org.traccar.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BillingServiceTest {

    private BillingService billing;
    private JdbcDataSource source;

    @BeforeEach
    public void setup() throws Exception {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tc_users (id INT PRIMARY KEY, administrator BOOLEAN DEFAULT FALSE, "
                    + "disabled BOOLEAN DEFAULT FALSE, expirationtime TIMESTAMP)");
            statement.execute("INSERT INTO tc_users (id,administrator) VALUES (1,TRUE),(2,FALSE),(3,FALSE)");
            try (var resources = new DirectoryResourceAccessor(Path.of("schema"))) {
                new Liquibase("changelog-mobgo-billing.xml", resources, new JdbcConnection(connection))
                        .update(new Contexts());
            }
        }
        billing = new BillingService(source, new ObjectMapper());
    }

    private BillingService.AccountInput input(String due, int version, boolean migrate) {
        return new BillingService.AccountInput(due, 10, "America/Manaus", version, migrate);
    }

    private String today() {
        return LocalDate.now(ZoneId.of("America/Manaus")).toString();
    }

    @Test
    public void tenthLateDayIsAllowedUntilLocalMidnight() {
        LocalDate due = LocalDate.of(2026, 11, 1);
        ZoneId zone = ZoneId.of("America/Manaus");
        assertEquals("current", BillingService.status(due, 10, zone, Instant.parse("2026-11-02T03:59:59Z")));
        assertEquals("grace", BillingService.status(due, 10, zone, Instant.parse("2026-11-02T04:00:00Z")));
        assertEquals("grace", BillingService.status(due, 10, zone, Instant.parse("2026-11-12T03:59:59Z")));
        assertEquals("suspended", BillingService.status(due, 10, zone, Instant.parse("2026-11-12T04:00:00Z")));
    }

    @Test
    public void noAccountPreservesLegacyAccessBehavior() throws Exception {
        assertNull(billing.get(2));
        billing.checkAccess(2);
        assertThrows(BillingService.Conflict.class, () -> billing.save(1, 1, input(today(), 0, false)));
    }

    @Test
    public void paymentRestoresAccessAndReplayDoesNotDuplicateLedger() throws Exception {
        billing.save(2, 1, input(LocalDate.now().minusDays(30).toString(), 0, false));
        assertThrows(SecurityException.class, () -> billing.checkAccess(2));
        var payment = new BillingService.PaymentInput(new BigDecimal("49.90"), today(), "receipt-1");
        var first = billing.confirm(2, 1, payment);
        var replay = billing.confirm(2, 1, payment);
        assertEquals(first.id(), replay.id());
        assertEquals(1, billing.payments(2).size());
        assertEquals(2, billing.changes(2).size());
        assertEquals("current", billing.get(2).status());
        billing.checkAccess(2);
        assertThrows(BillingService.Conflict.class, () -> billing.confirm(2, 1,
                new BillingService.PaymentInput(new BigDecimal("59.90"), today(), "receipt-1")));
    }

    @Test
    public void oldExpiryIsOnlyClearedDuringExplicitMigrationAndDisabledIsPreserved() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE tc_users SET expirationtime=TIMESTAMP '2020-01-01 00:00:00',disabled=TRUE "
                    + "WHERE id IN (2,3)");
        }
        billing.save(2, 1, input(today(), 0, false));
        billing.save(3, 1, input(today(), 0, true));
        assertNotNull(billing.get(3).legacyExpiration());
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id,expirationtime,disabled FROM tc_users WHERE id IN (2,3) "
                        + "ORDER BY id")) {
            rows.next();
            assertNotNull(rows.getTimestamp(2));
            rows.next();
            assertNull(rows.getTimestamp(2));
            assertEquals(true, rows.getBoolean(3));
        }
    }

    @Test
    public void staleAccountVersionCannotOverwriteAnotherOperator() throws Exception {
        billing.save(2, 1, input(today(), 0, false));
        assertThrows(BillingService.Conflict.class, () -> billing.save(2, 1, input("2030-01-01", 0, false)));
        assertEquals(today(), billing.get(2).dueDate());
        assertEquals(1, billing.changes(2).size());
    }

    @Test
    public void invalidPaymentsAndGraceCannotChangeBalance() throws Exception {
        billing.save(2, 1, input(today(), 0, false));
        assertThrows(IllegalArgumentException.class, () -> billing.confirm(2, 1,
                new BillingService.PaymentInput(new BigDecimal("0"), "2030-01-01", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> billing.save(2, 1,
                new BillingService.AccountInput(today(), -1, "America/Manaus", 1, false)));
        assertEquals(0, billing.payments(2).size());
        assertEquals(1, billing.changes(2).size());
    }
}

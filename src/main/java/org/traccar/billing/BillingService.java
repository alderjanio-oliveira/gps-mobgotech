package org.traccar.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.sql.DataSource;

@Singleton
public class BillingService {

    private final DataSource source;
    private final ObjectMapper mapper;

    public record AccountInput(String dueDate, Integer graceDays, String timeZone, int version,
            boolean migrateExpiration) {
    }

    public record Account(long userId, String dueDate, int graceDays, String timeZone, int version,
            String status, Date suspensionTime, Date legacyExpiration) {
    }

    public record PaymentInput(BigDecimal amount, String nextDueDate, String referenceKey) {
    }

    public record Payment(long id, long userId, long actorId, BigDecimal amount, String currency,
            String previousDue, String nextDue, Date paidAt, String referenceKey) {
    }

    public record Change(long id, long actorId, Date changedAt, String beforeValue, String afterValue, String reason) {
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    @Inject
    public BillingService(DataSource source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    private <T> T transaction(Work<T> work) throws StorageException {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (Exception error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                if (error instanceof Conflict conflict) {
                    throw conflict;
                }
                throw new StorageException(error);
            }
        } catch (SQLException error) {
            throw new StorageException(error);
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            statement.setObject(i + 1, value instanceof java.sql.Date ? value
                    : value instanceof Date date ? new Timestamp(date.getTime()) : value);
        }
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    public static String status(LocalDate due, int graceDays, ZoneId zone, Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        if (!today.isAfter(due)) {
            return "current";
        }
        return !today.isAfter(due.plusDays(graceDays)) ? "grace" : "suspended";
    }

    private Account account(Connection connection, long userId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM tc_billing_accounts WHERE userid=?")) {
            bind(statement, userId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                LocalDate due = rows.getDate("duedate").toLocalDate();
                int grace = rows.getInt("gracedays");
                ZoneId zone = ZoneId.of(rows.getString("timezone"));
                return new Account(userId, due.toString(), grace, zone.getId(), rows.getInt("version"),
                        status(due, grace, zone, Instant.now()),
                        Date.from(due.plusDays(grace + 1L).atStartOfDay(zone).toInstant()),
                        rows.getTimestamp("legacyexpiration"));
            }
        }
    }

    public Account get(long userId) throws StorageException {
        return transaction(connection -> account(connection, userId));
    }

    public void checkAccess(long userId) throws StorageException {
        Account account = get(userId);
        if (account != null && "suspended".equals(account.status())) {
            boolean admin = transaction(connection -> {
                try (var statement = connection.prepareStatement("SELECT administrator FROM tc_users WHERE id=?")) {
                    bind(statement, userId);
                    try (var rows = statement.executeQuery()) {
                        return rows.next() && rows.getBoolean(1);
                    }
                }
            });
            if (!admin) {
                throw new SecurityException("Billing access suspended after grace period");
            }
        }
    }

    private void audit(Connection connection, long userId, long actor, Account before, Account after, String reason)
            throws Exception {
        execute(connection, "INSERT INTO tc_billing_changes "
                        + "(userid,actorid,changedat,beforevalue,aftervalue,reason) VALUES (?,?,?,?,?,?)",
                userId, actor, new Date(), before == null ? null : mapper.writeValueAsString(before),
                mapper.writeValueAsString(after), reason);
    }

    public Account save(long userId, long actor, AccountInput input) throws StorageException {
        LocalDate due = LocalDate.parse(input.dueDate());
        int grace = input.graceDays() == null ? 10 : input.graceDays();
        ZoneId zone = ZoneId.of(input.timeZone() == null ? "America/Manaus" : input.timeZone());
        if (grace < 0 || grace > 365 || userId <= 0) {
            throw new IllegalArgumentException("Grace period must be between 0 and 365 days");
        }
        return transaction(connection -> {
            Account before = account(connection, userId);
            Date legacy = null;
            try (var statement = connection.prepareStatement("SELECT administrator,expirationtime FROM tc_users "
                    + "WHERE id=?")) {
                bind(statement, userId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getBoolean(1)) {
                        throw new Conflict("Billing requires an existing non-administrator user");
                    }
                    legacy = rows.getTimestamp(2);
                }
            }
            if (before == null) {
                if (input.version() != 0) {
                    throw new Conflict("Account changed; reload before saving");
                }
                execute(connection, "INSERT INTO tc_billing_accounts "
                                + "(userid,duedate,gracedays,timezone,version,legacyexpiration) VALUES (?,?,?,?,1,?)",
                        userId, java.sql.Date.valueOf(due), grace, zone.getId(),
                        input.migrateExpiration() ? legacy : null);
            } else {
                int changed = execute(connection, "UPDATE tc_billing_accounts SET duedate=?,gracedays=?,timezone=?,"
                                + "version=version+1 WHERE userid=? AND version=?",
                        java.sql.Date.valueOf(due), grace, zone.getId(), userId, input.version());
                if (changed == 0) {
                    throw new Conflict("Account changed; reload before saving");
                }
                if (input.migrateExpiration() && legacy != null) {
                    throw new Conflict("Legacy expiration migration is only allowed when creating the account");
                }
            }
            if (before == null && input.migrateExpiration()) {
                execute(connection, "UPDATE tc_users SET expirationtime=NULL WHERE id=?", userId);
            }
            Account after = account(connection, userId);
            audit(connection, userId, actor, before, after, "accountUpdated");
            return after;
        });
    }

    private static Payment payment(ResultSet rows) throws SQLException {
        return new Payment(rows.getLong("id"), rows.getLong("userid"), rows.getLong("actorid"),
                rows.getBigDecimal("amount"), rows.getString("currency"), rows.getDate("previousdue").toString(),
                rows.getDate("nextdue").toString(), rows.getTimestamp("paidat"), rows.getString("referencekey"));
    }

    public Payment confirm(long userId, long actor, PaymentInput input) throws StorageException {
        LocalDate next = LocalDate.parse(input.nextDueDate());
        if (input.amount() == null || input.amount().signum() <= 0 || input.amount().scale() > 2
                || input.amount().compareTo(new BigDecimal("99999999999999.99")) > 0
                || input.referenceKey() == null || input.referenceKey().isBlank()
                || input.referenceKey().length() > 128) {
            throw new IllegalArgumentException("Positive BRL amount and payment reference are required");
        }
        return transaction(connection -> {
            try (var statement = connection.prepareStatement("SELECT * FROM tc_billing_payments "
                    + "WHERE userid=? AND referencekey=?")) {
                bind(statement, userId, input.referenceKey());
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        Payment previous = payment(rows);
                        if (previous.amount().compareTo(input.amount()) != 0
                                || !previous.nextDue().equals(next.toString())) {
                            throw new Conflict("Payment reference was already used with different values");
                        }
                        return previous;
                    }
                }
            }
            Account before = account(connection, userId);
            if (before == null) {
                throw new Conflict("Configure the billing account first");
            }
            if (!next.isAfter(LocalDate.parse(before.dueDate()))
                    || next.isBefore(LocalDate.now(ZoneId.of(before.timeZone())))) {
                throw new Conflict("Next due date must advance the account and restore access");
            }
            int changed = execute(connection, "UPDATE tc_billing_accounts SET duedate=?,version=version+1 "
                    + "WHERE userid=? AND version=?", java.sql.Date.valueOf(next), userId, before.version());
            if (changed == 0) {
                throw new Conflict("Account changed; reload before confirming payment");
            }
            execute(connection, "INSERT INTO tc_billing_payments "
                            + "(userid,actorid,amount,currency,previousdue,nextdue,paidat,referencekey) "
                            + "VALUES (?,?,?,'BRL',?,?,?,?)", userId, actor, input.amount(),
                    java.sql.Date.valueOf(before.dueDate()), java.sql.Date.valueOf(next),
                    new Date(), input.referenceKey());
            audit(connection, userId, actor, before, account(connection, userId), "paymentConfirmed");
            try (var statement = connection.prepareStatement("SELECT * FROM tc_billing_payments "
                    + "WHERE userid=? AND referencekey=?")) {
                bind(statement, userId, input.referenceKey());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("Payment insert disappeared");
                    }
                    return payment(rows);
                }
            }
        });
    }

    public List<Payment> payments(long userId) throws StorageException {
        return transaction(connection -> {
            List<Payment> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM tc_billing_payments "
                    + "WHERE userid=? ORDER BY id DESC")) {
                bind(statement, userId);
                statement.setMaxRows(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(payment(rows));
                    }
                }
            }
            return result;
        });
    }

    public List<Change> changes(long userId) throws StorageException {
        return transaction(connection -> {
            List<Change> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM tc_billing_changes "
                    + "WHERE userid=? ORDER BY id DESC")) {
                bind(statement, userId);
                statement.setMaxRows(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new Change(rows.getLong("id"), rows.getLong("actorid"),
                                rows.getTimestamp("changedat"), rows.getString("beforevalue"),
                                rows.getString("aftervalue"), rows.getString("reason")));
                    }
                }
            }
            return result;
        });
    }
}

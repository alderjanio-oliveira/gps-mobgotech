package org.traccar.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

@Singleton
public class NotificationOutbox {

    public static final int MAX_ATTEMPTS = 5;
    private static final long LEASE_MILLIS = 120000;
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public record Recipient(long notificationId, long userId, String channel) {
    }

    public record Delivery(long id, long eventId, long deviceId, long notificationId, long userId,
            String channel, String state, int attempts, Date nextAttempt, String token, String errorCode) {
    }

    public record Attempt(int number, Date startedAt, Date finishedAt, String outcome, String errorCode) {
    }

    @Inject
    public NotificationOutbox(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run(Connection connection) throws Exception;
    }

    private <T> T transaction(Transaction<T> work) throws StorageException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
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
            statement.setObject(i + 1, value instanceof Date date ? new Timestamp(date.getTime()) : value);
        }
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    @FunctionalInterface
    public interface DurableChange {
        boolean apply(Connection connection) throws Exception;
    }

    public boolean change(DurableChange change) throws StorageException {
        return transaction(change::apply);
    }

    /** Event, domain transition and durable fan-out marker commit together. */
    public boolean save(Event event, Position position) throws StorageException {
        return save(event, position, null);
    }

    public boolean save(Event event, Position position, DurableChange change) throws StorageException {
        long id = transaction(connection -> {
            if (change != null && !change.apply(connection)) {
                return 0L;
            }
            Date now = new Date();
            if (Event.TYPE_DISTANCE_REMINDER.equals(event.getType()) && event.getDistanceReminderId() > 0) {
                if (position == null || !position.hasAttribute(Position.KEY_TOTAL_DISTANCE)) {
                    return 0L;
                }
                double total = position.getDouble(Position.KEY_TOTAL_DISTANCE);
                try (var statement = connection.prepareStatement(
                        "SELECT startvalue, thresholddistance FROM tc_distance_reminders WHERE id=? AND status=?")) {
                    bind(statement, event.getDistanceReminderId(), "pending");
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            return 0L;
                        }
                        double start = rows.getDouble(1);
                        double threshold = rows.getDouble(2);
                        if (!Double.isFinite(total) || !Double.isFinite(start) || !Double.isFinite(threshold)
                                || start < 0 || threshold <= 0 || total - start < threshold) {
                            return 0L;
                        }
                        int changed = execute(connection, "UPDATE tc_distance_reminders SET status=?, notifiedat=? "
                                        + "WHERE id=? AND status=? AND startvalue=? AND thresholddistance=?",
                                "notified", now, event.getDistanceReminderId(), "pending", start, threshold);
                        if (changed == 0) {
                            return 0L;
                        }
                        event.set("traveledDistance", total - start);
                        event.set("thresholdDistance", threshold);
                    }
                }
            }
            long eventId;
            try (var statement = connection.prepareStatement("INSERT INTO tc_events "
                    + "(type,servertime,eventtime,deviceid,positionid,geofenceid,maintenanceid,"
                    + "distancereminderid,attributes) "
                    + "VALUES (?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, event.getType(), now, event.getEventTime() == null ? now : event.getEventTime(),
                        event.getDeviceId(), event.getPositionId(), event.getGeofenceId(), event.getMaintenanceId(),
                        event.getDistanceReminderId(), mapper.writeValueAsString(event.getAttributes()));
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Event insert returned no key");
                    }
                    eventId = keys.getLong(1);
                }
            }
            execute(connection, "INSERT INTO tc_event_outbox (eventid,positionsnapshot,state,attempts,nextattempt) "
                            + "VALUES (?,?,?,0,?)", eventId,
                    position == null ? null : mapper.writeValueAsString(position), "pending", now);
            return eventId;
        });
        if (id > 0) {
            event.setId(id);
        }
        return id > 0;
    }

    public List<Long> pendingEvents() throws StorageException {
        return transaction(connection -> {
            execute(connection, "UPDATE tc_event_outbox SET state='failed',errorcode='leaseExpired' "
                    + "WHERE state='pending' AND attempts>=5 AND leaseuntil<?", new Date());
            List<Long> ids = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT eventid FROM tc_event_outbox "
                    + "WHERE state='pending' AND nextattempt<=? AND (leaseuntil IS NULL OR leaseuntil<?) "
                    + "ORDER BY nextattempt,eventid")) {
                bind(statement, new Date(), new Date());
                statement.setMaxRows(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getLong(1));
                    }
                }
            }
            return ids;
        });
    }

    public String claimEvent(long eventId) throws StorageException {
        String token = UUID.randomUUID().toString();
        return transaction(connection -> execute(connection, "UPDATE tc_event_outbox "
                        + "SET claimtoken=?,leaseuntil=?,attempts=attempts+1 WHERE eventid=? AND state='pending' "
                        + "AND attempts<5 AND nextattempt<=? AND (leaseuntil IS NULL OR leaseuntil<?)",
                token, new Date(System.currentTimeMillis() + LEASE_MILLIS), eventId, new Date(), new Date()) == 1
                ? token : null);
    }

    public Position position(long eventId) throws StorageException {
        return transaction(connection -> {
            try (var statement = connection.prepareStatement("SELECT positionsnapshot FROM tc_event_outbox "
                    + "WHERE eventid=?")) {
                bind(statement, eventId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        return null;
                    }
                    return mapper.readValue(rows.getString(1), Position.class);
                }
            }
        });
    }

    public void expand(Event event, String token, List<Recipient> recipients, String state) throws StorageException {
        transaction(connection -> {
            if (execute(connection, "UPDATE tc_event_outbox SET leaseuntil=? WHERE eventid=? "
                            + "AND claimtoken=? AND state='pending'",
                    new Date(System.currentTimeMillis() + LEASE_MILLIS),
                    event.getId(), token) == 0) {
                return null;
            }
            for (Recipient recipient : recipients) {
                execute(connection, "INSERT INTO tc_notification_deliveries "
                                + "(eventid,deviceid,notificationid,userid,channel,state,attempts,nextattempt) "
                                + "VALUES (?,?,?,?,?,'pending',0,?)", event.getId(), event.getDeviceId(),
                        recipient.notificationId(), recipient.userId(), recipient.channel(), new Date());
            }
            execute(connection, "UPDATE tc_event_outbox SET state=?,claimtoken=NULL,leaseuntil=NULL,errorcode=NULL "
                    + "WHERE eventid=? AND claimtoken=?", state, event.getId(), token);
            return null;
        });
    }

    public void failEvent(long eventId, String token, String errorCode) throws StorageException {
        transaction(connection -> {
            execute(connection, "UPDATE tc_event_outbox SET state=CASE WHEN attempts>=5 THEN 'failed' "
                            + "ELSE 'pending' END,nextattempt=?,leaseuntil=NULL,claimtoken=NULL,errorcode=? "
                            + "WHERE eventid=? AND claimtoken=?",
                    new Date(System.currentTimeMillis() + 60000), errorCode, eventId, token);
            return null;
        });
    }

    private static Delivery delivery(ResultSet row) throws SQLException {
        return new Delivery(row.getLong("id"), row.getLong("eventid"), row.getLong("deviceid"),
                row.getLong("notificationid"), row.getLong("userid"), row.getString("channel"), row.getString("state"),
                row.getInt("attempts"), row.getTimestamp("nextattempt"), row.getString("claimtoken"),
                row.getString("errorcode"));
    }

    public List<Delivery> dueDeliveries() throws StorageException {
        return transaction(connection -> {
            Date now = new Date();
            execute(connection, "UPDATE tc_notification_deliveries SET state=CASE "
                            + "WHEN channel='command' THEN 'uncertain' WHEN attempts>=5 THEN 'failed' "
                            + "ELSE 'pending' END, "
                            + "leaseuntil=NULL,claimtoken=NULL,errorcode='leaseExpired' "
                            + "WHERE state='sending' AND leaseuntil<?", now);
            execute(connection, "UPDATE tc_notification_attempts SET outcome='unknown',finishedat=? "
                            + "WHERE outcome='started' AND deliveryid IN (SELECT id FROM tc_notification_deliveries "
                            + "WHERE errorcode='leaseExpired' AND state IN ('pending','failed','uncertain'))", now);
            List<Delivery> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM tc_notification_deliveries "
                    + "WHERE state='pending' AND nextattempt<=? ORDER BY nextattempt,id")) {
                bind(statement, now);
                statement.setMaxRows(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(delivery(rows));
                    }
                }
            }
            return result;
        });
    }

    public Delivery claim(Delivery candidate) throws StorageException {
        return transaction(connection -> {
            String token = UUID.randomUUID().toString();
            Date now = new Date();
            int changed = execute(connection, "UPDATE tc_notification_deliveries "
                            + "SET state='sending',attempts=attempts+1,leaseuntil=?,claimtoken=? "
                            + "WHERE id=? AND state='pending' AND nextattempt<=? AND attempts<?",
                    new Date(now.getTime() + LEASE_MILLIS), token, candidate.id(), now, MAX_ATTEMPTS);
            if (changed == 0) {
                return null;
            }
            int attempt;
            try (var statement = connection.prepareStatement(
                    "SELECT attempts FROM tc_notification_deliveries WHERE id=? AND claimtoken=?")) {
                bind(statement, candidate.id(), token);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("Claimed delivery disappeared");
                    }
                    attempt = rows.getInt(1);
                }
            }
            execute(connection, "INSERT INTO tc_notification_attempts (deliveryid,attempt,startedat,outcome) "
                    + "VALUES (?,?,?,'started')", candidate.id(), attempt, now);
            return new Delivery(candidate.id(), candidate.eventId(), candidate.deviceId(), candidate.notificationId(),
                    candidate.userId(), candidate.channel(), "sending", attempt, now, token, null);
        });
    }

    public void finish(Delivery delivery, String outcome, String errorCode) throws StorageException {
        transaction(connection -> {
            boolean retry = "failed".equals(outcome) && delivery.attempts() < MAX_ATTEMPTS
                    && !"command".equals(delivery.channel());
            String state = retry ? "pending" : "command".equals(delivery.channel()) && "failed".equals(outcome)
                    ? "uncertain" : outcome;
            Date now = new Date();
            Date next = new Date(now.getTime() + retryDelayMillis(delivery.attempts()));
            int changed = execute(connection, "UPDATE tc_notification_deliveries "
                            + "SET state=?,nextattempt=?,leaseuntil=NULL,claimtoken=NULL,errorcode=? "
                            + "WHERE id=? AND claimtoken=? AND state='sending'",
                    state, next, errorCode, delivery.id(), delivery.token());
            if (changed > 0) {
                execute(connection, "UPDATE tc_notification_attempts SET finishedat=?,outcome=?,errorcode=? "
                                + "WHERE deliveryid=? AND attempt=? AND outcome='started'",
                        now, outcome, errorCode, delivery.id(), delivery.attempts());
            }
            return null;
        });
    }

    public static long retryDelayMillis(int attempts) {
        return Math.min(3600000, 5000L * (1L << Math.min(attempts, 9)));
    }

    public List<Delivery> deliveries(long deviceId, long userId) throws StorageException {
        return transaction(connection -> {
            List<Delivery> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM tc_notification_deliveries "
                    + "WHERE deviceid=? AND userid=? ORDER BY id DESC")) {
                bind(statement, deviceId, userId);
                statement.setMaxRows(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(delivery(rows));
                    }
                }
            }
            return result;
        });
    }

    public List<Attempt> attempts(long deliveryId, long deviceId, long userId) throws StorageException {
        return transaction(connection -> {
            List<Attempt> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT a.* FROM tc_notification_attempts a "
                    + "JOIN tc_notification_deliveries d ON d.id=a.deliveryid "
                    + "WHERE d.id=? AND d.deviceid=? AND d.userid=? ORDER BY a.attempt")) {
                bind(statement, deliveryId, deviceId, userId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new Attempt(rows.getInt("attempt"), rows.getTimestamp("startedat"),
                                rows.getTimestamp("finishedat"), rows.getString("outcome"),
                                rows.getString("errorcode")));
                    }
                }
            }
            return result;
        });
    }
}

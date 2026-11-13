package org.traccar.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.traccar.model.Command;
import org.traccar.model.Position;
import org.traccar.model.QueuedCommand;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;

@Singleton
public class CommandLedger {

    private final DataSource source;
    private final ObjectMapper mapper;

    public record Begin(String id, boolean newRequest, String state, Long queuedCommandId) {
    }

    public record RequestView(String id, long userId, String type, String state, Date requestedAt, Date updatedAt,
            Long queuedCommandId, String reportedState, Date evidenceTime, String errorCode) {
    }

    public record ResponseView(long id, Date receivedAt, Date deviceTime, String response, String state,
            String correlation) {
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    @Inject
    public CommandLedger(DataSource source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
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
            statement.setObject(i + 1, value instanceof Date date ? new Timestamp(date.getTime()) : value);
        }
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Begin existing(Connection connection, long device, long user, String key, String fingerprint)
            throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM tc_command_requests "
                + "WHERE deviceid=? AND userid=? AND clientkey=?")) {
            bind(statement, device, user, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                if (!fingerprint.equals(rows.getString("fingerprint"))) {
                    throw new Conflict("Command reference was already used with different values");
                }
                Number queued = (Number) rows.getObject("queuedcommandid");
                return new Begin(rows.getString("requestid"), false, rows.getString("state"),
                        queued == null ? null : queued.longValue());
            }
        }
    }

    public static boolean relayCommand(Command command) {
        if (Command.TYPE_ENGINE_STOP.equals(command.getType())
                || Command.TYPE_ENGINE_RESUME.equals(command.getType())) {
            return true;
        }
        String data = command.getString(Command.KEY_DATA);
        return Command.TYPE_CUSTOM.equals(command.getType()) && data != null
                && data.toUpperCase(Locale.ROOT).matches("RELAY[, ]+[01]#?");
    }

    public Begin begin(Command command, long userId, String suppliedKey) throws StorageException {
        if (command.getDeviceId() <= 0 || command.getType() == null || command.getType().isBlank()) {
            throw new IllegalArgumentException("Device and command type are required");
        }
        String key = suppliedKey == null || suppliedKey.isBlank() ? UUID.randomUUID().toString() : suppliedKey;
        if (key.length() > 128) {
            throw new IllegalArgumentException("Command reference is too long");
        }
        String fingerprint;
        try {
            fingerprint = hash(command.getType() + ":" + command.getTextChannel() + ":"
                    + mapper.writeValueAsString(command.getAttributes()));
        } catch (Exception error) {
            throw new StorageException(error);
        }
        try {
            return transaction(connection -> {
                Begin previous = existing(connection, command.getDeviceId(), userId, key, fingerprint);
                if (previous != null) {
                    return previous;
                }
                Date now = new Date();
                if (relayCommand(command)) {
                    boolean found;
                    int version = 0;
                    try (var statement = connection.prepareStatement(
                            "SELECT * FROM tc_command_dedupe WHERE deviceid=?")) {
                        bind(statement, command.getDeviceId());
                        try (var rows = statement.executeQuery()) {
                            found = rows.next();
                            if (found) {
                                version = rows.getInt("version");
                                if (fingerprint.equals(rows.getString("fingerprint"))
                                        && now.getTime() - rows.getTimestamp("requestedat").getTime() < 10000) {
                                    throw new Conflict("Relay command was submitted recently; check its history");
                                }
                            }
                        }
                    }
                    if (found) {
                        if (execute(connection, "UPDATE tc_command_dedupe "
                                        + "SET fingerprint=?,requestedat=?,version=version+1 "
                                        + "WHERE deviceid=? AND version=?",
                                fingerprint, now, command.getDeviceId(), version) == 0) {
                            throw new Conflict("Another relay command is being submitted");
                        }
                    } else {
                        execute(connection, "INSERT INTO tc_command_dedupe (deviceid,fingerprint,requestedat,version) "
                                + "VALUES (?,?,?,1)", command.getDeviceId(), fingerprint, now);
                    }
                }
                String id = UUID.randomUUID().toString();
                execute(connection, "INSERT INTO tc_command_requests "
                                + "(requestid,deviceid,userid,clientkey,fingerprint,commandtype,state,requestedat,"
                                + "updatedat) "
                                + "VALUES (?,?,?,?,?,?,'requested',?,?)", id, command.getDeviceId(), userId,
                        key, fingerprint, command.getType(), now, now);
                return new Begin(id, true, "requested", null);
            });
        } catch (StorageException error) {
            Begin previous = transaction(connection ->
                    existing(connection, command.getDeviceId(), userId, key, fingerprint));
            if (previous != null) {
                return previous;
            }
            throw error;
        }
    }

    public void dispatched(String id, QueuedCommand queued) throws StorageException {
        transaction(connection -> {
            execute(connection, "UPDATE tc_command_requests "
                            + "SET state=?,updatedat=?,queuedcommandid=? WHERE requestid=?",
                    queued == null ? "sent" : "queued", new Date(), queued == null ? null : queued.getId(), id);
            return null;
        });
    }

    public void failed(String id, Throwable error) throws StorageException {
        transaction(connection -> {
            execute(connection, "UPDATE tc_command_requests SET state='uncertain',updatedat=?,errorcode=? "
                    + "WHERE requestid=?", new Date(), error.getClass().getSimpleName(), id);
            return null;
        });
    }

    public void released(long queueId) throws StorageException {
        transaction(connection -> {
            execute(connection, "UPDATE tc_command_requests SET state='queueReleased',updatedat=? "
                    + "WHERE queuedcommandid=? AND state='queued'", new Date(), queueId);
            return null;
        });
    }

    public void observe(Position position) throws StorageException {
        if (position.getBoolean(Position.KEY_ARCHIVE) || position.getServerTime() == null) {
            return;
        }
        transaction(connection -> {
            Date received = position.getServerTime();
            Object raw = position.getAttributes().get(Position.KEY_RESULT);
            if (raw instanceof String response) {
                String key = hash(position.getDeviceId() + ":" + received.getTime() + ":" + response);
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM tc_command_responses WHERE responsekey=?")) {
                    bind(statement, key);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            String state = response.toLowerCase(Locale.ROOT).contains("executed after speed")
                                    ? "deferred" : "responseReceived";
                            execute(connection, "INSERT INTO tc_command_responses "
                                            + "(deviceid,responsekey,receivedat,devicetime,response,state) "
                                            + "VALUES (?,?,?,?,?,?)",
                                    position.getDeviceId(), key, received, position.getDeviceTime(),
                                    response.substring(0, Math.min(response.length(), 4000)), state);
                        }
                    }
                }
            }
            Object value = position.getAttributes().get(Position.KEY_BLOCKED);
            Boolean blocked = value instanceof Boolean bool ? bool
                    : value instanceof Number number && (number.doubleValue() == 0 || number.doubleValue() == 1)
                    ? number.doubleValue() == 1 : null;
            Date time = position.getDeviceTime();
            if (blocked != null && time != null && !time.after(received)) {
                execute(connection, "UPDATE tc_command_requests SET reportedstate=?,evidencetime=? "
                                + "WHERE deviceid=? AND requestedat<? AND (evidencetime IS NULL OR evidencetime<?) "
                                + "AND commandtype IN ('engineStop','engineResume')",
                        blocked ? "blocked" : "unblocked", time, position.getDeviceId(), time, time);
            }
            return null;
        });
    }

    public List<RequestView> requests(long deviceId) throws StorageException {
        return transaction(connection -> {
            List<RequestView> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM tc_command_requests "
                    + "WHERE deviceid=? ORDER BY requestedat DESC,requestid")) {
                bind(statement, deviceId);
                statement.setMaxRows(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Number queued = (Number) rows.getObject("queuedcommandid");
                        result.add(new RequestView(rows.getString("requestid"), rows.getLong("userid"),
                                rows.getString("commandtype"), rows.getString("state"),
                                rows.getTimestamp("requestedat"),
                                rows.getTimestamp("updatedat"), queued == null ? null : queued.longValue(),
                                rows.getString("reportedstate"), rows.getTimestamp("evidencetime"),
                                rows.getString("errorcode")));
                    }
                }
            }
            return result;
        });
    }

    public List<ResponseView> responses(long deviceId) throws StorageException {
        return transaction(connection -> {
            List<ResponseView> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM tc_command_responses "
                    + "WHERE deviceid=? ORDER BY id DESC")) {
                bind(statement, deviceId);
                statement.setMaxRows(100);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new ResponseView(rows.getLong("id"), rows.getTimestamp("receivedat"),
                                rows.getTimestamp("devicetime"), rows.getString("response"), rows.getString("state"),
                                "unconfirmed"));
                    }
                }
            }
            return result;
        });
    }
}

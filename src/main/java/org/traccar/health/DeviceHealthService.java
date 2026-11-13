package org.traccar.health;

import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.notification.NotificationOutbox;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

@Singleton
public class DeviceHealthService {

    private final NotificationOutbox outbox;
    private final DataSource source;

    public record Incident(long id, String kind, Date openedAt, Date recoveredAt, String recoveryReason) {
    }

    @Inject
    public DeviceHealthService(NotificationOutbox outbox, DataSource source) {
        this.outbox = outbox;
        this.source = source;
    }

    private static int execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                statement.setObject(i + 1, value instanceof Date date ? new Timestamp(date.getTime()) : value);
            }
            return statement.executeUpdate();
        }
    }

    public static long threshold(Device device, String key, int fallback) {
        int minutes = device.hasAttribute(key) ? device.getInteger(key) : fallback;
        return Math.max(0, Math.min(10080, minutes)) * 60000L;
    }

    public static Boolean silent(Device device, Date now, long threshold) {
        Date last = device.getLastUpdate();
        return last == null || last.after(now) ? null : now.getTime() - last.getTime() >= threshold;
    }

    private void transition(Device device, Position position, Date now, String kind, boolean problem,
            String openType, String recoveredType, long openDelay, long recoveryDelay) throws StorageException {
        NotificationOutbox.DurableChange change = connection -> {
            String state = null;
            int version = 0;
            Date candidate = null;
            try (var statement = connection.prepareStatement(
                    "SELECT state,version,candidateat FROM tc_device_health WHERE deviceid=? AND kind=?")) {
                statement.setLong(1, device.getId());
                statement.setString(2, kind);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        state = rows.getString(1);
                        version = rows.getInt(2);
                        candidate = rows.getTimestamp(3);
                    }
                }
            }
            if (state == null) {
                execute(connection, "INSERT INTO tc_device_health "
                                + "(deviceid,kind,state,observedat,changedat,version) VALUES (?,?,'normal',?,?,0)",
                        device.getId(), kind, now, now);
                state = "normal";
            }
            if (problem == "active".equals(state)) {
                if (candidate != null) {
                    execute(connection, "UPDATE tc_device_health SET candidateat=NULL,version=version+1 "
                            + "WHERE deviceid=? AND kind=? AND version=?", device.getId(), kind, version);
                }
                return false;
            }
            long delay = problem ? openDelay : recoveryDelay;
            if (delay > 0 && (candidate == null || now.getTime() - candidate.getTime() < delay)) {
                if (candidate == null) {
                    execute(connection, "UPDATE tc_device_health SET candidateat=?,version=version+1 "
                            + "WHERE deviceid=? AND kind=? AND version=?", now, device.getId(), kind, version);
                }
                return false;
            }
            int changed = execute(connection, "UPDATE tc_device_health "
                            + "SET state=?,changedat=?,candidateat=NULL,version=version+1 "
                            + "WHERE deviceid=? AND kind=? AND version=?", problem ? "active" : "normal", now,
                    device.getId(), kind, version);
            if (changed == 0) {
                return false;
            }
            if (problem) {
                execute(connection, "INSERT INTO tc_device_incidents (deviceid,kind,openedat) VALUES (?,?,?)",
                        device.getId(), kind, now);
            } else {
                execute(connection, "UPDATE tc_device_incidents SET recoveredat=?,recoveryreason='recovered' "
                                + "WHERE deviceid=? AND kind=? AND recoveredat IS NULL", now, device.getId(), kind);
            }
            return true;
        };
        boolean publishRecovery = !device.hasAttribute("monitoring.recovery")
                || device.getBoolean("monitoring.recovery");
        if (!problem && !publishRecovery) {
            outbox.change(change);
            return;
        }
        Event event = new Event(problem ? openType : recoveredType, device.getId());
        event.setEventTime(now);
        event.set("incidentKind", kind);
        event.set("causeConfirmed", false);
        if (device.getLastUpdate() != null) {
            event.set("lastCommunication", device.getLastUpdate().getTime());
        }
        if (position != null) {
            event.setPositionId(position.getId());
            if (position.hasAttribute(Position.KEY_IGNITION)) {
                event.set("lastKnownIgnition", position.getBoolean(Position.KEY_IGNITION));
            }
        }
        outbox.save(event, position, change);
    }

    public void evaluate(Device device, Position position, Date now) throws StorageException {
        if (!device.getBoolean("monitoring.enabled")) {
            deactivate(device.getId(), now);
            return;
        }
        long communication = threshold(device, "monitoring.silenceMinutes", 15);
        if (communication == 0) {
            deactivateKind(device.getId(), "communication", now);
        }
        Boolean silent = silent(device, now, communication > 0 ? communication : 900000);
        if (silent == null) {
            return;
        }
        long recovery = Math.max(0, Math.min(3600, device.hasAttribute("monitoring.recoverySeconds")
                ? device.getInteger("monitoring.recoverySeconds") : 60)) * 1000L;
        if (communication > 0) {
            transition(device, position, now, "communication", silent,
                    Event.TYPE_COMMUNICATION_SILENCE, Event.TYPE_COMMUNICATION_RECOVERED, 0, recovery);
        }
        long gps = threshold(device, "monitoring.gpsMinutes", 30);
        if (!silent && gps > 0) {
            boolean invalid = position == null || !position.getValid() || position.getFixTime() == null
                    || position.getFixTime().after(now);
            boolean unavailable = invalid || now.getTime() - position.getFixTime().getTime() >= gps;
            transition(device, position, now, "gps", unavailable,
                    Event.TYPE_GPS_UNAVAILABLE, Event.TYPE_GPS_RECOVERED, invalid ? gps : 0, recovery);
        } else if (gps == 0) {
            deactivateKind(device.getId(), "gps", now);
        }
        long ignition = threshold(device, "monitoring.ignitionMinutes", 0);
        if (ignition > 0 && position != null && position.hasAttribute(Position.KEY_IGNITION)) {
            boolean offlineIgnition = position.getBoolean(Position.KEY_IGNITION)
                    && Boolean.TRUE.equals(silent(device, now, ignition));
            transition(device, position, now, "offlineIgnition", offlineIgnition,
                    Event.TYPE_OFFLINE_IGNITION, Event.TYPE_OFFLINE_IGNITION_RECOVERED, 0, recovery);
        } else if (ignition == 0) {
            deactivateKind(device.getId(), "offlineIgnition", now);
        }
    }

    private void deactivateKind(long deviceId, String kind, Date now) throws StorageException {
        outbox.change(connection -> {
            execute(connection, "UPDATE tc_device_health SET state='disabled',candidateat=NULL,"
                    + "changedat=?,version=version+1 WHERE deviceid=? AND kind=? "
                    + "AND (state='active' OR candidateat IS NOT NULL)", now, deviceId, kind);
            execute(connection, "UPDATE tc_device_incidents SET recoveredat=?,recoveryreason='ruleDisabled' "
                    + "WHERE deviceid=? AND kind=? AND recoveredat IS NULL", now, deviceId, kind);
            return true;
        });
    }

    public void deactivate(long deviceId, Date now) throws StorageException {
        outbox.change(connection -> {
            execute(connection, "UPDATE tc_device_health "
                    + "SET state='disabled',candidateat=NULL,changedat=?,version=version+1 "
                    + "WHERE deviceid=? AND state='active'", now, deviceId);
            execute(connection, "UPDATE tc_device_incidents SET recoveredat=?,recoveryreason='monitoringDisabled' "
                    + "WHERE deviceid=? AND recoveredat IS NULL", now, deviceId);
            return true;
        });
    }

    public Set<Long> activeDeviceIds() throws StorageException {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT DISTINCT deviceid FROM tc_device_health WHERE state='active'")) {
            Set<Long> result = new HashSet<>();
            while (rows.next()) {
                result.add(rows.getLong(1));
            }
            return result;
        } catch (SQLException error) {
            throw new StorageException(error);
        }
    }

    public List<String> activeKinds(long deviceId) throws StorageException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "SELECT kind FROM tc_device_health WHERE deviceid=? AND state='active' ORDER BY kind")) {
            statement.setLong(1, deviceId);
            try (var rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
                return result;
            }
        } catch (SQLException error) {
            throw new StorageException(error);
        }
    }

    public List<Incident> incidents(long deviceId) throws StorageException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM tc_device_incidents WHERE deviceid=? ORDER BY id DESC")) {
            statement.setLong(1, deviceId);
            statement.setMaxRows(100);
            try (var rows = statement.executeQuery()) {
                List<Incident> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new Incident(rows.getLong("id"), rows.getString("kind"), rows.getTimestamp("openedat"),
                            rows.getTimestamp("recoveredat"), rows.getString("recoveryreason")));
                }
                return result;
            }
        } catch (SQLException error) {
            throw new StorageException(error);
        }
    }
}

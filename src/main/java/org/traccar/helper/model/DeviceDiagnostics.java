package org.traccar.helper.model;

import org.traccar.model.Device;
import org.traccar.model.Position;

import java.util.Date;

public final class DeviceDiagnostics {

    private DeviceDiagnostics() {
    }

    public record Snapshot(
            long deviceId, String communicationStatus, String positionStatus,
            Date lastCommunication, Date lastPosition, Date lastPositionReceived,
            Long communicationAgeSeconds, Long positionAgeSeconds,
            Boolean ignition, Boolean charging, Double speedKnots,
            String blockState, String blockRequestStatus, boolean causeConfirmed) {
    }

    private static Long age(Date time, Date now) {
        return time == null ? null : (now.getTime() - time.getTime()) / 1000;
    }

    public static Snapshot describe(Device device, Position position, Date now, long staleSeconds) {
        if (staleSeconds <= 0) {
            throw new IllegalArgumentException("Stale threshold must be positive");
        }
        Long positionAge = position == null ? null : age(position.getFixTime(), now);
        String positionStatus;
        if (position == null || positionAge == null) {
            positionStatus = "unavailable";
        } else if (position.getFixTime().after(now)) {
            positionStatus = "futureTimestamp";
        } else if (!position.getValid()) {
            positionStatus = "invalid";
        } else if (positionAge > staleSeconds) {
            positionStatus = "stale";
        } else {
            positionStatus = "recent";
        }
        return new Snapshot(device.getId(), device.getStatus(), positionStatus,
                device.getLastUpdate(), position == null ? null : position.getFixTime(),
                position == null ? null : position.getServerTime(), age(device.getLastUpdate(), now), positionAge,
                position != null && position.hasAttribute(Position.KEY_IGNITION)
                        ? position.getBoolean(Position.KEY_IGNITION) : null,
                position != null && position.hasAttribute(Position.KEY_CHARGE)
                        ? position.getBoolean(Position.KEY_CHARGE) : null,
                position != null ? position.getSpeed() : null,
                device.getBlockState(), device.getBlockRequestStatus(), false);
    }
}

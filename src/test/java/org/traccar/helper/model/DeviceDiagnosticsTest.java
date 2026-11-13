package org.traccar.helper.model;

import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Position;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DeviceDiagnosticsTest {

    @Test
    public void connectionDoesNotImplyRecentGpsOrConfirmedCause() {
        Device device = new Device();
        device.setStatus(Device.STATUS_ONLINE);
        Position position = new Position();
        position.setValid(true);
        position.setFixTime(new Date(0));
        var diagnostics = DeviceDiagnostics.describe(device, position, new Date(1000000), 900);
        assertEquals(Device.STATUS_ONLINE, diagnostics.communicationStatus());
        assertEquals("stale", diagnostics.positionStatus());
        assertNull(diagnostics.ignition());
        assertFalse(diagnostics.causeConfirmed());
    }

    @Test
    public void unavailableInvalidAndFutureFixesAreDistinct() {
        Device device = new Device();
        assertEquals("unavailable", DeviceDiagnostics.describe(device, null, new Date(1000), 900).positionStatus());
        Position position = new Position();
        position.setFixTime(new Date(0));
        position.setValid(false);
        assertEquals("invalid", DeviceDiagnostics.describe(device, position, new Date(1000), 900).positionStatus());
        position.setFixTime(new Date(2000));
        assertEquals("futureTimestamp",
                DeviceDiagnostics.describe(device, position, new Date(1000), 900).positionStatus());
    }

    @Test
    public void offlineCanRetainLastKnownIgnitionAndRecentPosition() {
        Device device = new Device();
        device.setStatus(Device.STATUS_OFFLINE);
        Position position = new Position();
        position.setFixTime(new Date(0));
        position.setValid(true);
        position.set(Position.KEY_IGNITION, true);
        var diagnostics = DeviceDiagnostics.describe(device, position, new Date(1000), 900);
        assertEquals(Device.STATUS_OFFLINE, diagnostics.communicationStatus());
        assertEquals("recent", diagnostics.positionStatus());
        assertEquals(Boolean.TRUE, diagnostics.ignition());
    }
}

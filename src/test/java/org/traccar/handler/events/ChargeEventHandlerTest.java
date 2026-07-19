package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ChargeEventHandlerTest extends BaseTest {

    @Test
    public void testChargeEventHandler() {
        Position lastPosition = new Position();
        lastPosition.setDeviceId(1);
        lastPosition.setFixTime(new Date(0));
        lastPosition.set(Position.KEY_CHARGE, true);

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1000));
        position.set(Position.KEY_CHARGE, false);

        var device = mock(Device.class);
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);

        ChargeEventHandler chargeEventHandler = new ChargeEventHandler(cacheManager);

        List<Event> events = new ArrayList<>();
        chargeEventHandler.analyzePosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_CHARGE_DISCONNECTED, events.get(0).getType());

        lastPosition.set(Position.KEY_CHARGE, false);
        position.set(Position.KEY_CHARGE, true);
        events.clear();
        chargeEventHandler.analyzePosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_CHARGE_CONNECTED, events.get(0).getType());

        lastPosition.set(Position.KEY_CHARGE, true);
        position.set(Position.KEY_CHARGE, true);
        events.clear();
        chargeEventHandler.analyzePosition(position, events::add);

        assertTrue(events.isEmpty());
    }

}

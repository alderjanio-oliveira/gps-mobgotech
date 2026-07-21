package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

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
        var device = new Device();
        device.setId(1);

        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        var storage = mock(Storage.class);
        ChargeEventHandler chargeEventHandler = new ChargeEventHandler(cacheManager, storage);

        List<Event> events = new ArrayList<>();

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1000));
        position.set(Position.KEY_CHARGE, true);
        chargeEventHandler.analyzePosition(position, events::add);
        assertTrue(events.isEmpty());

        // posição sem o atributo no meio (comum no protocolo real) não pode quebrar a comparação
        Position noAttribute = new Position();
        noAttribute.setDeviceId(1);
        noAttribute.setFixTime(new Date(2000));
        chargeEventHandler.analyzePosition(noAttribute, events::add);
        assertTrue(events.isEmpty());

        position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(3000));
        position.set(Position.KEY_CHARGE, false);
        chargeEventHandler.analyzePosition(position, events::add);
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_CHARGE_DISCONNECTED, events.get(0).getType());

        events.clear();
        position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(4000));
        position.set(Position.KEY_CHARGE, true);
        chargeEventHandler.analyzePosition(position, events::add);
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_CHARGE_CONNECTED, events.get(0).getType());

        events.clear();
        position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(5000));
        position.set(Position.KEY_CHARGE, true);
        chargeEventHandler.analyzePosition(position, events::add);
        assertTrue(events.isEmpty());
    }

}

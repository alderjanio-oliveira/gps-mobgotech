package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.DistanceReminder;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DistanceReminderEventHandlerTest extends BaseTest {

    @Test
    public void testDistanceReminderEventHandler() throws Exception {
        Position lastPosition = new Position();
        lastPosition.setDeviceId(1);
        lastPosition.setFixTime(new Date(0));

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1000));

        var reminder = new DistanceReminder();
        reminder.setId(1);
        reminder.setName("Troca de óleo");
        reminder.setThresholdDistance(1000);
        reminder.setStatus(DistanceReminder.STATUS_PENDING);

        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);
        when(cacheManager.getDeviceObjects(anyLong(), eq(DistanceReminder.class))).thenReturn(Set.of(reminder));

        var storage = mock(Storage.class);
        DistanceReminderEventHandler eventHandler = new DistanceReminderEventHandler(cacheManager, storage);

        List<Event> events = new ArrayList<>();

        position.set(Position.KEY_TOTAL_DISTANCE, 5000.0);
        eventHandler.analyzePosition(position, events::add);
        assertTrue(events.isEmpty());
        assertEquals(5000.0, reminder.getStartValue());

        position.set(Position.KEY_TOTAL_DISTANCE, 5999.0);
        eventHandler.analyzePosition(position, events::add);
        assertTrue(events.isEmpty());

        position.set(Position.KEY_TOTAL_DISTANCE, 6001.0);
        eventHandler.analyzePosition(position, events::add);
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_DISTANCE_REMINDER, events.get(0).getType());
        assertEquals(DistanceReminder.STATUS_NOTIFIED, reminder.getStatus());

        events.clear();
        eventHandler.analyzePosition(position, events::add);
        assertTrue(events.isEmpty());
    }

}

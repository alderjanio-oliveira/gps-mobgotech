package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.DistanceReminder;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;

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
        reminder.setStartValue(5000);
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

    @Test
    public void testZeroStartIsValidAndNotReset() throws Exception {
        Fixture fixture = new Fixture(0, 1000);
        fixture.position.set(Position.KEY_TOTAL_DISTANCE, 999.0);
        fixture.run();
        assertTrue(fixture.events.isEmpty());
        assertEquals(0, fixture.reminder.getStartValue());
        fixture.position.set(Position.KEY_TOTAL_DISTANCE, 1000.0);
        fixture.run();
        assertEquals(1, fixture.events.size());
        fixture.run();
        assertEquals(1, fixture.events.size());
    }

    @Test
    public void testStorageFailureDoesNotConsumeReminder() throws Exception {
        Fixture fixture = new Fixture(0, 1000);
        fixture.position.set(Position.KEY_TOTAL_DISTANCE, 1000.0);
        org.mockito.Mockito.doThrow(new StorageException("unavailable"))
                .doNothing().when(fixture.storage).updateObject(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        fixture.run();
        assertTrue(fixture.events.isEmpty());
        assertEquals(DistanceReminder.STATUS_PENDING, fixture.reminder.getStatus());
        fixture.run();
        assertEquals(1, fixture.events.size());
    }

    @Test
    public void testArchivedAndOldPositionsIgnored() throws Exception {
        Fixture fixture = new Fixture(0, 1000);
        fixture.position.set(Position.KEY_TOTAL_DISTANCE, 1000.0);
        fixture.position.set(Position.KEY_ARCHIVE, true);
        fixture.run();
        fixture.position.set(Position.KEY_ARCHIVE, false);
        fixture.position.setFixTime(new Date(-1));
        fixture.run();
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    public void testInvalidAndCancelledRemindersIgnored() throws Exception {
        Fixture fixture = new Fixture(0, 1000);
        fixture.position.set(Position.KEY_TOTAL_DISTANCE, Double.POSITIVE_INFINITY);
        fixture.run();
        fixture.position.set(Position.KEY_TOTAL_DISTANCE, 1000.0);
        fixture.reminder.setStatus(DistanceReminder.STATUS_CANCELLED);
        fixture.run();
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    public void testFirstPositionAfterRestartCanReachSavedTarget() throws Exception {
        Fixture fixture = new Fixture(5000, 1000);
        org.mockito.Mockito.when(fixture.cacheManager.getPosition(anyLong())).thenReturn(null);
        fixture.position.set(Position.KEY_TOTAL_DISTANCE, 6000.0);
        fixture.run();
        assertEquals(1, fixture.events.size());
        assertEquals(1000.0, fixture.events.get(0).getDouble("traveledDistance"));
    }

    private static class Fixture {
        private final DistanceReminder reminder = new DistanceReminder();
        private final Position position = new Position();
        private final Storage storage = mock(Storage.class);
        private final CacheManager cacheManager = mock(CacheManager.class);
        private final List<Event> events = new ArrayList<>();
        private final DistanceReminderEventHandler handler;

        Fixture(double start, double threshold) {
            reminder.setId(1);
            reminder.setStartValue(start);
            reminder.setThresholdDistance(threshold);
            Position previous = new Position();
            previous.setFixTime(new Date(0));
            position.setDeviceId(1);
            position.setFixTime(new Date(1000));
            position.setServerTime(new Date(2000));
            when(cacheManager.getPosition(anyLong())).thenReturn(previous);
            when(cacheManager.getDeviceObjects(anyLong(), eq(DistanceReminder.class))).thenReturn(Set.of(reminder));
            handler = new DistanceReminderEventHandler(cacheManager, storage);
        }

        void run() {
            handler.analyzePosition(position, events::add);
        }
    }

}

package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DistanceHandlerTest {

    @Test
    public void testCalculateDistance() {

        DistanceHandler distanceHandler = new DistanceHandler(new Config(), mock(CacheManager.class));

        Position position = new Position();
        distanceHandler.handlePosition(position, p -> {});

        assertEquals(0.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(0.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

        position.set(Position.KEY_DISTANCE, 100);

        distanceHandler.handlePosition(position, p -> {});

        assertEquals(100.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(100.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

    }

    @Test
    public void invalidGpsCannotInflateAccumulatedDistance() {
        var cache = mock(CacheManager.class);
        Position last = new Position();
        last.setValid(true);
        last.setLatitude(-3.1);
        last.setLongitude(-60.0);
        last.set(Position.KEY_TOTAL_DISTANCE, 15000000.0);
        when(cache.getPosition(1)).thenReturn(last);
        Position position = new Position();
        position.setDeviceId(1);
        position.setValid(false);
        position.setLatitude(0);
        position.setLongitude(0);
        DistanceHandler handler = new DistanceHandler(new Config(), cache);
        handler.handlePosition(position, p -> {});
        assertEquals(0, position.getDouble(Position.KEY_DISTANCE));
        assertEquals(15000000, position.getDouble(Position.KEY_TOTAL_DISTANCE));
        position.setValid(true);
        position.setLatitude(Double.NaN);
        handler.handlePosition(position, p -> {});
        assertEquals(15000000, position.getDouble(Position.KEY_TOTAL_DISTANCE));
    }

    @Test
    public void negativeOrNonFiniteReportedDistanceCannotPoisonOdometer() {
        var handler = new DistanceHandler(new Config(), mock(CacheManager.class));
        for (double distance : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            Position position = new Position();
            position.set(Position.KEY_DISTANCE, distance);
            handler.handlePosition(position, p -> {});
            assertEquals(0, position.getDouble(Position.KEY_TOTAL_DISTANCE));
        }
    }

}

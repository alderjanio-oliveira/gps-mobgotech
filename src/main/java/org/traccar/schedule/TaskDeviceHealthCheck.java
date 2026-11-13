package org.traccar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.health.DeviceHealthService;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskDeviceHealthCheck extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDeviceHealthCheck.class);
    private final DeviceHealthService health;
    private final Storage storage;

    @Inject
    public TaskDeviceHealthCheck(DeviceHealthService health, Storage storage) {
        this.health = health;
        this.storage = storage;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleWithFixedDelay(this, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        try {
            var active = health.activeDeviceIds();
            for (var device : storage.getObjects(Device.class, new Request(new Columns.All()))) {
                try {
                    if (!device.getBoolean("monitoring.enabled")) {
                        if (active.contains(device.getId())) {
                            health.deactivate(device.getId(), new Date());
                        }
                        continue;
                    }
                    Position position = storage.getObject(Position.class, new Request(new Columns.All(),
                            new Condition.LatestPositions(device.getId())));
                    health.evaluate(device, position, new Date());
                } catch (Exception error) {
                    LOGGER.warn("Device health check failed: device={}, error={}",
                            device.getId(), error.getClass().getSimpleName());
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Device health check unavailable: {}", error.getClass().getSimpleName());
        }
    }
}

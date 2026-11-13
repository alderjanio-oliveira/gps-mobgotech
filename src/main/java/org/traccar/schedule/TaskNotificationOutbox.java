package org.traccar.schedule;

import org.traccar.database.NotificationManager;

import jakarta.inject.Inject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskNotificationOutbox extends SingleScheduleTask {

    private final NotificationManager notificationManager;

    @Inject
    public TaskNotificationOutbox(NotificationManager notificationManager) {
        this.notificationManager = notificationManager;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleWithFixedDelay(this, 2, 2, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        notificationManager.drainOutbox();
    }
}

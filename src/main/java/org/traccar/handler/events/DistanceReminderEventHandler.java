/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.DistanceReminder;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

public class DistanceReminderEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistanceReminderEventHandler.class);

    private final CacheManager cacheManager;
    private final Storage storage;

    @Inject
    public DistanceReminderEventHandler(CacheManager cacheManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.storage = storage;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!position.hasAttribute(Position.KEY_TOTAL_DISTANCE)) {
            return;
        }

        Position lastPosition = cacheManager.getPosition(position.getDeviceId());
        if (lastPosition == null || position.getFixTime().compareTo(lastPosition.getFixTime()) < 0) {
            return;
        }

        double totalDistance = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        for (DistanceReminder reminder
                : cacheManager.getDeviceObjects(position.getDeviceId(), DistanceReminder.class)) {
            if (!DistanceReminder.STATUS_PENDING.equals(reminder.getStatus())) {
                continue;
            }
            try {
                if (reminder.getStartValue() <= 0) {
                    reminder.setStartValue(totalDistance);
                    storage.updateObject(reminder, new Request(
                            new Columns.Include("startValue"),
                            new Condition.Equals("id", reminder.getId())));
                    continue;
                }

                double traveled = totalDistance - reminder.getStartValue();
                if (reminder.getThresholdDistance() > 0 && traveled >= reminder.getThresholdDistance()) {
                    reminder.setStatus(DistanceReminder.STATUS_NOTIFIED);
                    reminder.setNotifiedAt(position.getDeviceTime());
                    storage.updateObject(reminder, new Request(
                            new Columns.Include("status", "notifiedAt"),
                            new Condition.Equals("id", reminder.getId())));

                    Event event = new Event(Event.TYPE_DISTANCE_REMINDER, position);
                    event.setDistanceReminderId(reminder.getId());
                    callback.eventDetected(event);
                }
            } catch (StorageException e) {
                LOGGER.warn("Update distance reminder error", e);
            }
        }
    }

}

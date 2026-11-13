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

import org.traccar.model.DistanceReminder;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import jakarta.inject.Inject;

public class DistanceReminderEventHandler extends BasePositionEventHandler {

    @Inject
    public DistanceReminderEventHandler(CacheManager cacheManager) {
        super(cacheManager);
    }

    @Override
    protected void onPosition(Position position, Position lastPosition, Callback callback) {
        if (!position.hasAttribute(Position.KEY_TOTAL_DISTANCE)) {
            return;
        }

        double totalDistance = position.getDouble(Position.KEY_TOTAL_DISTANCE);

        if (!Double.isFinite(totalDistance) || totalDistance < 0 || position.getBoolean(Position.KEY_ARCHIVE)) {
            return;
        }

        for (DistanceReminder reminder
                : cacheManager.getDeviceObjects(position.getDeviceId(), DistanceReminder.class)) {
            if (!DistanceReminder.STATUS_PENDING.equals(reminder.getStatus())) {
                continue;
            }
            double traveled = totalDistance - reminder.getStartValue();
            if (Double.isFinite(traveled) && reminder.getStartValue() >= 0
                    && Double.isFinite(reminder.getThresholdDistance())
                    && reminder.getThresholdDistance() > 0 && traveled >= reminder.getThresholdDistance()) {
                Event event = new Event(Event.TYPE_DISTANCE_REMINDER, position);
                event.setDistanceReminderId(reminder.getId());
                event.set("traveledDistance", traveled);
                event.set("thresholdDistance", reminder.getThresholdDistance());
                callback.eventDetected(event);
                if (event.getId() > 0) {
                    reminder.setStatus(DistanceReminder.STATUS_NOTIFIED);
                    reminder.setNotifiedAt(position.getServerTime());
                }
            }
        }
    }

}

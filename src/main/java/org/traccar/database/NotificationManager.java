/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.forward.EventData;
import org.traccar.forward.EventForwarder;
import org.traccar.geocoder.Geocoder;
import org.traccar.model.Calendar;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Maintenance;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.notification.NotificationOutbox;
import org.traccar.notification.NotificatorManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Singleton
public class NotificationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationManager.class);

    private final Storage storage;
    private final NotificationOutbox outbox;
    private final CacheManager cacheManager;
    private final EventForwarder eventForwarder;
    private final NotificatorManager notificatorManager;
    private final Geocoder geocoder;

    private final boolean geocodeOnRequest;
    private final long timeThreshold;
    private final Set<Long> blockedUsers = new HashSet<>();

    @Inject
    public NotificationManager(
            Config config, Storage storage, CacheManager cacheManager, @Nullable EventForwarder eventForwarder,
            NotificatorManager notificatorManager, @Nullable Geocoder geocoder, NotificationOutbox outbox) {
        this.storage = storage;
        this.outbox = outbox;
        this.cacheManager = cacheManager;
        this.eventForwarder = eventForwarder;
        this.notificatorManager = notificatorManager;
        this.geocoder = geocoder;
        geocodeOnRequest = config.getBoolean(Keys.GEOCODER_ON_REQUEST);
        timeThreshold = config.getLong(Keys.NOTIFICATOR_TIME_THRESHOLD);
        String blockedUsersString = config.getString(Keys.NOTIFICATION_BLOCK_USERS);
        if (blockedUsersString != null) {
            for (String userIdString : blockedUsersString.split(",")) {
                blockedUsers.add(Long.parseLong(userIdString));
            }
        }
    }

    private void updateEvent(Event event, Position position) throws StorageException {
        if (outbox.save(event, position)) {
            LOGGER.info("Event queued: event={}, device={}, type={}",
                    event.getId(), event.getDeviceId(), event.getType());
        }
    }

    private List<Notification> matchingNotifications(Event event) {
        var notifications = cacheManager.getDeviceNotifications(event.getDeviceId()).stream()
                .filter(notification -> notification.getType().equals(event.getType()))
                .filter(notification -> {
                    if (event.getType().equals(Event.TYPE_ALARM)) {
                        String alarmsAttribute = notification.getString("alarms");
                        if (alarmsAttribute != null) {
                            return Arrays.asList(alarmsAttribute.split(","))
                                    .contains(event.getString(Position.KEY_ALARM));
                        }
                        return false;
                    }
                    return true;
                })
                .filter(notification -> {
                    String geofenceIdsString = notification.getString("geofenceIds");
                    if (geofenceIdsString != null) {
                        return Arrays.stream(geofenceIdsString.split(",")).map(Long::parseLong)
                                .anyMatch(geofenceId -> geofenceId == event.getGeofenceId());
                    }
                    return true;
                })
                .filter(notification -> {
                    long calendarId = notification.getCalendarId();
                    Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
                    return calendar == null || calendar.checkMoment(event.getEventTime());
                })
                .toList();

        return notifications;
    }

    private void expandEvent(Event event, Position position, String token) throws StorageException {
        if (System.currentTimeMillis() - event.getEventTime().getTime() > timeThreshold) {
            outbox.expand(event, token, List.of(), "expired");
            return;
        }
        var recipients = new ArrayList<NotificationOutbox.Recipient>();
        for (var notification : matchingNotifications(event)) {
            for (var user : cacheManager.getNotificationUsers(notification.getId(), event.getDeviceId())) {
                if (!blockedUsers.contains(user.getId()) && !user.getDisabled()) {
                    for (String channel : notification.getNotificatorsTypes()) {
                        if (!channel.isBlank()) {
                            recipients.add(new NotificationOutbox.Recipient(
                                    notification.getId(), user.getId(), channel));
                        }
                    }
                }
            }
        }
        outbox.expand(event, token, recipients, recipients.isEmpty() ? "noRecipients" : "expanded");
        forwardEvent(event, position);
    }

    public void drainOutbox() {
        try {
            for (long id : outbox.pendingEvents()) {
                String token = outbox.claimEvent(id);
                if (token == null) {
                    continue;
                }
                Event event = storage.getObject(Event.class, new Request(new Columns.All(),
                        new Condition.Equals("id", id)));
                if (event == null) {
                    continue;
                }
                Object reference = new Object();
                try {
                    cacheManager.addDevice(event.getDeviceId(), reference);
                    expandEvent(event, outbox.position(id), token);
                } catch (Exception error) {
                    outbox.failEvent(id, token, error.getClass().getSimpleName());
                    LOGGER.warn("Event fan-out failed: event={}, error={}", id, error.getClass().getSimpleName());
                } finally {
                    cacheManager.removeDevice(event.getDeviceId(), reference);
                }
            }
            for (var candidate : outbox.dueDeliveries()) {
                var delivery = outbox.claim(candidate);
                if (delivery != null) {
                    deliver(delivery);
                }
            }
        } catch (StorageException error) {
            LOGGER.warn("Notification outbox unavailable: {}", error.getClass().getSimpleName());
        }
    }

    private void finishDelivery(NotificationOutbox.Delivery delivery, String state, String errorCode) {
        try {
            outbox.finish(delivery, state, errorCode);
        } catch (StorageException error) {
            LOGGER.warn("Notification result persistence failed: delivery={}", delivery.id());
        }
    }

    private void deliver(NotificationOutbox.Delivery delivery) {
        Object reference = new Object();
        try {
            cacheManager.addDevice(delivery.deviceId(), reference);
            var user = cacheManager.getNotificationUsers(delivery.notificationId(), delivery.deviceId()).stream()
                    .filter(value -> value.getId() == delivery.userId()).findFirst().orElse(null);
            var notification = cacheManager.getDeviceNotifications(delivery.deviceId()).stream()
                    .filter(value -> value.getId() == delivery.notificationId()).findFirst().orElse(null);
            if (user == null || user.getDisabled() || blockedUsers.contains(delivery.userId()) || notification == null
                    || !notification.getNotificatorsTypes().contains(delivery.channel())) {
                finishDelivery(delivery, "cancelled", "recipientOrChannelRevoked");
                return;
            }
            user.checkDisabled();
            Event event = storage.getObject(Event.class, new Request(new Columns.All(),
                    new Condition.Equals("id", delivery.eventId())));
            if (event == null || !notification.getType().equals(event.getType())) {
                finishDelivery(delivery, "cancelled", "eventOrRuleRemoved");
                return;
            }
            Position position = outbox.position(delivery.eventId());
            if (position != null && position.getAddress() == null && geocodeOnRequest && geocoder != null) {
                position.setAddress(geocoder.getAddress(position.getLatitude(), position.getLongitude(), null));
            }
            notificatorManager.getNotificator(delivery.channel()).sendAsync(notification, user, event, position)
                    .orTimeout(30, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
                        finishDelivery(delivery, error == null ? "accepted" : "failed",
                                error == null ? null : error.getClass().getSimpleName());
                    });
        } catch (SecurityException error) {
            finishDelivery(delivery, "cancelled", "accountDisabledOrExpired");
        } catch (Exception error) {
            finishDelivery(delivery, "failed", error.getClass().getSimpleName());
        } finally {
            cacheManager.removeDevice(delivery.deviceId(), reference);
        }
    }

    private void forwardEvent(Event event, Position position) {
        if (eventForwarder != null) {
            EventData eventData = new EventData();
            eventData.setEvent(event);
            eventData.setPosition(position);
            eventData.setDevice(cacheManager.getObject(Device.class, event.getDeviceId()));
            if (event.getGeofenceId() != 0) {
                eventData.setGeofence(cacheManager.getObject(Geofence.class, event.getGeofenceId()));
            }
            if (event.getMaintenanceId() != 0) {
                eventData.setMaintenance(cacheManager.getObject(Maintenance.class, event.getMaintenanceId()));
            }
            eventForwarder.forward(eventData).exceptionally(throwable -> {
                LOGGER.warn("Event forwarding failed", throwable);
                return null;
            });
        }
    }

    public void updateEvents(Map<Event, Position> events) {
        for (Entry<Event, Position> entry : events.entrySet()) {
            Event event = entry.getKey();
            Position position = entry.getValue();
            var key = new Object();
            try {
                cacheManager.addDevice(event.getDeviceId(), key);
                updateEvent(event, position);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                cacheManager.removeDevice(event.getDeviceId(), key);
            }
        }
    }
}

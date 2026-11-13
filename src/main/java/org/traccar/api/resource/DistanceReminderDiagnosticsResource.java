package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.DistanceReminder;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.notification.NotificatorManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Path("devices/{deviceId}/distancereminders/{id}/diagnostics")
@Produces(MediaType.APPLICATION_JSON)
public class DistanceReminderDiagnosticsResource extends BaseResource {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private NotificatorManager notificatorManager;

    public record Diagnostics(
            long deviceId, long reminderId, String reminderStatus, Double traveledDistance, double thresholdDistance,
            boolean targetReached, Long eventId, Date eventTime, int notificationRulesForCurrentUser,
            Set<String> configuredChannels, Set<String> enabledChannels, List<String> blockers, String deliveryStatus) {
    }

    @GET
    public Diagnostics get(@PathParam("deviceId") long deviceId, @PathParam("id") long id) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkPermission(DistanceReminder.class, getUserId(), id);
        if (storage.getPermissions(Device.class, deviceId, DistanceReminder.class, id).isEmpty()) {
            throw new NotFoundException();
        }
        DistanceReminder reminder = storage.getObject(DistanceReminder.class,
                new Request(new Columns.All(), new Condition.Equals("id", id)));
        if (reminder == null) {
            throw new NotFoundException();
        }
        Object reference = new Object();
        cacheManager.addDevice(deviceId, reference);
        try {
            Position position = cacheManager.getPosition(deviceId);
            Double traveled = position != null && position.hasAttribute(Position.KEY_TOTAL_DISTANCE)
                    ? position.getDouble(Position.KEY_TOTAL_DISTANCE) - reminder.getStartValue() : null;
            Event event = storage.getObject(Event.class, new Request(new Columns.All(),
                    new Condition.And(new Condition.Equals("deviceId", deviceId),
                            new Condition.Equals("distanceReminderId", id)), new Order("id", true, 1)));
            var rules = cacheManager.getDeviceNotifications(deviceId).stream()
                    .filter(rule -> Event.TYPE_DISTANCE_REMINDER.equals(rule.getType()))
                    .filter(rule -> cacheManager.getNotificationUsers(rule.getId(), deviceId).stream()
                            .anyMatch(user -> user.getId() == getUserId()))
                    .toList();
            Set<String> configured = rules.stream().flatMap(rule -> rule.getNotificatorsTypes().stream())
                    .collect(Collectors.toSet());
            Set<String> enabled = notificatorManager.getAllNotificatorTypes().stream()
                    .map(type -> type.type()).collect(Collectors.toSet());
            List<String> blockers = blockers(reminder.getStatus(), traveled, reminder.getThresholdDistance(),
                    event != null, rules.size(), configured, enabled);
            return new Diagnostics(deviceId, id, reminder.getStatus(), traveled, reminder.getThresholdDistance(),
                    traveled != null && Double.isFinite(traveled) && reminder.getThresholdDistance() > 0
                            && traveled >= reminder.getThresholdDistance(),
                    event == null ? null : event.getId(), event == null ? null : event.getEventTime(), rules.size(),
                    configured, enabled, blockers, "unknown");
        } finally {
            cacheManager.removeDevice(deviceId, reference);
        }
    }

    public static List<String> blockers(String status, Double traveled, double threshold,
            boolean eventExists, int rules, Set<String> configured, Set<String> enabled) {
        List<String> result = new ArrayList<>();
        if (DistanceReminder.STATUS_DONE.equals(status) || DistanceReminder.STATUS_CANCELLED.equals(status)) {
            result.add("reminderClosed");
        }
        if (!Double.isFinite(threshold) || threshold <= 0) {
            result.add("invalidThreshold");
        }
        if (traveled == null || !Double.isFinite(traveled)) {
            result.add("odometerUnavailable");
        } else if (traveled < threshold) {
            result.add("targetNotReached");
        }
        if (DistanceReminder.STATUS_NOTIFIED.equals(status) && !eventExists) {
            result.add("reachedWithoutSavedEvent");
        }
        if (rules == 0) {
            result.add("noNotificationRuleForCurrentUser");
        } else {
            Set<String> active = new HashSet<>(configured);
            active.retainAll(enabled);
            if (active.isEmpty()) {
                result.add("noEnabledNotificationChannel");
            }
        }
        return result;
    }
}

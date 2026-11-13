package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.notification.NotificationOutbox;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Date;
import java.util.List;

@Path("devices/{deviceId}/notification-deliveries")
@Produces(MediaType.APPLICATION_JSON)
public class NotificationDeliveryResource extends BaseResource {

    @Inject
    private NotificationOutbox outbox;

    public record DeliveryView(long id, long eventId, long notificationId, long userId, String channel,
            String state, int attempts, Date nextAttempt, String errorCode) {
    }

    private long recipient(long deviceId, long userId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        long recipientId = userId == 0 ? getUserId() : userId;
        permissionsService.checkUser(getUserId(), recipientId);
        return recipientId;
    }

    @GET
    public List<DeliveryView> get(@PathParam("deviceId") long deviceId, @QueryParam("userId") long userId)
            throws StorageException {
        long recipientId = recipient(deviceId, userId);
        return outbox.deliveries(deviceId, recipientId).stream().map(value -> new DeliveryView(
                value.id(), value.eventId(), value.notificationId(), value.userId(), value.channel(), value.state(),
                value.attempts(), value.nextAttempt(), value.errorCode())).toList();
    }

    @GET
    @Path("{id}/attempts")
    public List<NotificationOutbox.Attempt> attempts(@PathParam("deviceId") long deviceId, @PathParam("id") long id,
            @QueryParam("userId") long userId) throws StorageException {
        return outbox.attempts(id, deviceId, recipient(deviceId, userId));
    }
}

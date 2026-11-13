package org.traccar.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.NotificationOutbox;
import org.traccar.notification.NotificatorManager;
import org.traccar.notificators.Notificator;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NotificationManagerTest {

    private NotificationManager manager;
    private NotificatorManager notificators;
    private Notificator mail;
    private Notificator web;
    private Notification notification;
    private User user;
    private Event event;

    @BeforeEach
    public void setup() throws Exception {
        var cache = mock(CacheManager.class);
        var storage = mock(Storage.class);
        notificators = mock(NotificatorManager.class);
        mail = mock(Notificator.class);
        web = mock(Notificator.class);
        notification = new Notification() {
            @Override
            public Set<String> getNotificatorsTypes() {
                return new LinkedHashSet<>(List.of("disabled", "mail", "web"));
            }
        };
        notification.setId(10);
        notification.setType(Event.TYPE_DISTANCE_REMINDER);
        user = new User();
        user.setId(5);
        Device device = new Device();
        device.setId(1);
        device.setUniqueId("test-device");
        event = new Event(Event.TYPE_DISTANCE_REMINDER, 1);
        when(storage.addObject(eq(event), any())).thenReturn(100L);
        when(cache.getDeviceNotifications(1)).thenReturn(Set.of(notification));
        when(cache.getNotificationUsers(10, 1)).thenReturn(Set.of(user));
        when(cache.getObject(Device.class, 1)).thenReturn(device);
        when(notificators.getNotificator("disabled")).thenThrow(new IllegalStateException("disabled"));
        when(notificators.getNotificator("mail")).thenReturn(mail);
        when(notificators.getNotificator("web")).thenReturn(web);
        when(mail.sendAsync(eq(notification), eq(user), eq(event), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(web.sendAsync(eq(notification), eq(user), eq(event), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        var outbox = mock(NotificationOutbox.class);
        when(outbox.save(any(), any())).thenAnswer(invocation -> {
            ((Event) invocation.getArgument(0)).setId(100);
            return true;
        });
        when(outbox.pendingEvents()).thenReturn(List.of(100L));
        when(outbox.claimEvent(100)).thenReturn("token");
        when(storage.getObject(eq(Event.class), any())).thenReturn(event);
        List<NotificationOutbox.Delivery> deliveries = new ArrayList<>();
        for (String channel : List.of("disabled", "mail", "web")) {
            deliveries.add(new NotificationOutbox.Delivery(deliveries.size() + 1, 100, 1, 10, 5,
                    channel, "sending", 1, new Date(), "token", null));
        }
        when(outbox.dueDeliveries()).thenReturn(deliveries);
        when(outbox.claim(any())).thenAnswer(invocation -> invocation.getArgument(0));
        manager = new NotificationManager(new Config(), storage, cache, null, notificators, null, outbox);
    }

    @Test
    public void disabledChannelDoesNotSuppressMailOrWeb() {
        manager.updateEvents(Map.of(event, new Position()));
        manager.drainOutbox();
        verify(mail).sendAsync(eq(notification), eq(user), eq(event), any());
        verify(web).sendAsync(eq(notification), eq(user), eq(event), any());
    }

    @Test
    public void asyncMailFailureDoesNotSuppressWeb() {
        when(mail.sendAsync(eq(notification), eq(user), eq(event), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SMTP unavailable")));
        manager.updateEvents(Map.of(event, new Position()));
        manager.drainOutbox();
        verify(web).sendAsync(eq(notification), eq(user), eq(event), any());
    }
}

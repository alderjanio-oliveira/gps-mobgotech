package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import org.traccar.api.security.PermissionsService;
import org.traccar.model.Device;
import org.traccar.model.DistanceReminder;
import org.traccar.storage.Storage;

import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class DistanceReminderDiagnosticsResourceTest {

    @Test
    public void savedReminderDoesNotImplyConfiguredNotification() {
        var blockers = DistanceReminderDiagnosticsResource.blockers(
                DistanceReminder.STATUS_NOTIFIED, 1000.0, 1000, true, 0, Set.of(), Set.of("mail"));
        assertEquals(List.of("noNotificationRuleForCurrentUser"), blockers);
    }

    @Test
    public void persistedReachingWithoutEventIsVisible() {
        var blockers = DistanceReminderDiagnosticsResource.blockers(
                DistanceReminder.STATUS_NOTIFIED, 1000.0, 1000, false, 1, Set.of("mail"), Set.of("mail"));
        assertTrue(blockers.contains("reachedWithoutSavedEvent"));
    }

    @Test
    public void disabledChannelIsDistinctFromMissingRule() {
        var blockers = DistanceReminderDiagnosticsResource.blockers(
                DistanceReminder.STATUS_PENDING, 1000.0, 1000, false, 1, Set.of("mail"), Set.of("web"));
        assertEquals(List.of("noEnabledNotificationChannel"), blockers);
    }

    @Test
    public void pendingTargetHasNoDeliveryFailureYet() {
        var blockers = DistanceReminderDiagnosticsResource.blockers(
                DistanceReminder.STATUS_PENDING, 999.0, 1000, false, 1, Set.of("web"), Set.of("web"));
        assertEquals(List.of("targetNotReached"), blockers);
    }

    private static class TestResource extends DistanceReminderDiagnosticsResource {
        TestResource(Storage storage, PermissionsService permissions) {
            this.storage = storage;
            this.permissionsService = permissions;
        }

        @Override
        protected long getUserId() {
            return 1;
        }
    }

    @Test
    public void foreignDeviceCannotExposeReminderDiagnostics() throws Exception {
        var storage = mock(Storage.class);
        var permissions = mock(PermissionsService.class);
        doThrow(new SecurityException("Forbidden")).when(permissions).checkPermission(Device.class, 1, 2);
        var resource = new TestResource(storage, permissions);
        assertThrows(SecurityException.class, () -> resource.get(2, 3));
        verifyNoInteractions(storage);
    }

    @Test
    public void foreignReminderCannotExposeDeliveryConfiguration() throws Exception {
        var storage = mock(Storage.class);
        var permissions = mock(PermissionsService.class);
        doThrow(new SecurityException("Forbidden"))
                .when(permissions).checkPermission(DistanceReminder.class, 1, 3);
        var resource = new TestResource(storage, permissions);
        assertThrows(SecurityException.class, () -> resource.get(2, 3));
        verifyNoInteractions(storage);
    }

    @Test
    public void unrelatedReminderAndDeviceAreRejected() {
        var resource = new TestResource(mock(Storage.class), mock(PermissionsService.class));
        assertThrows(NotFoundException.class, () -> resource.get(2, 3));
    }

}

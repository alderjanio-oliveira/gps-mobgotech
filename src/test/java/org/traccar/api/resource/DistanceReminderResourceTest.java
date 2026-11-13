package org.traccar.api.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.security.PermissionsService;
import org.traccar.model.DistanceReminder;
import org.traccar.storage.Storage;

import jakarta.ws.rs.BadRequestException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class DistanceReminderResourceTest {

    private Storage storage;
    private PermissionsService permissions;
    private DistanceReminder reminder;
    private TestResource resource;

    private static class TestResource extends DistanceReminderResource {
        TestResource(Storage storage, PermissionsService permissions) {
            this.storage = storage;
            this.permissionsService = permissions;
        }

        @Override
        protected long getUserId() {
            return 1;
        }
    }

    @BeforeEach
    public void setup() throws Exception {
        storage = mock(Storage.class);
        permissions = mock(PermissionsService.class);
        resource = new TestResource(storage, permissions);
        reminder = new DistanceReminder();
        reminder.setId(2);
        when(storage.getObject(eq(DistanceReminder.class), any())).thenReturn(reminder);
    }

    @Test
    public void readonlyCannotConfirmOrCancel() throws Exception {
        doThrow(new SecurityException("Readonly"))
                .when(permissions).checkEdit(1, DistanceReminder.class, false, false);
        assertThrows(SecurityException.class, () -> resource.confirm(2));
        assertThrows(SecurityException.class, () -> resource.cancel(2));
        verifyNoInteractions(storage);
    }

    @Test
    public void foreignReminderIsDeniedBeforeReading() throws Exception {
        doThrow(new SecurityException("Forbidden"))
                .when(permissions).checkPermission(DistanceReminder.class, 1, 2);
        assertThrows(SecurityException.class, () -> resource.confirm(2));
        assertThrows(SecurityException.class, () -> resource.cancel(2));
        verifyNoInteractions(storage);
    }

    @Test
    public void repeatedConfirmationPreservesFinalDistanceAndDate() throws Exception {
        reminder.setStatus(DistanceReminder.STATUS_DONE);
        reminder.setTraveledDistance(1250);
        assertEquals(200, resource.confirm(2).getStatus());
        assertEquals(1250, reminder.getTraveledDistance());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void cancelledReminderCannotBeConfirmed() throws Exception {
        reminder.setStatus(DistanceReminder.STATUS_CANCELLED);
        assertEquals(409, resource.confirm(2).getStatus());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void doneReminderCannotBeCancelled() throws Exception {
        reminder.setStatus(DistanceReminder.STATUS_DONE);
        assertEquals(409, resource.cancel(2).getStatus());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void repeatedCancellationIsIdempotent() throws Exception {
        reminder.setStatus(DistanceReminder.STATUS_CANCELLED);
        assertEquals(200, resource.cancel(2).getStatus());
        verify(storage, never()).updateObject(any(), any());
    }

    @Test
    public void invalidThresholdsAreRejectedBeforePersistence() {
        for (double threshold : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            DistanceReminder invalid = new DistanceReminder();
            invalid.setName("Oil");
            invalid.setThresholdDistance(threshold);
            assertThrows(BadRequestException.class, () -> resource.add(invalid));
        }
        verifyNoInteractions(storage);
    }

    @Test
    public void notifiedReminderCannotBeRearmedThroughGenericUpdate() throws Exception {
        reminder.setName("Oil");
        reminder.setThresholdDistance(1000);
        reminder.setStatus(DistanceReminder.STATUS_NOTIFIED);
        assertEquals(409, resource.update(reminder).getStatus());
        verify(storage, never()).updateObject(any(), any());
    }

}

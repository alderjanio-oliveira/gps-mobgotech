package org.traccar.api.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.security.PermissionsService;
import org.traccar.model.Device;
import org.traccar.model.DistanceReminder;
import org.traccar.model.LinkedDevice;
import org.traccar.model.Permission;
import org.traccar.storage.Storage;

import jakarta.ws.rs.BadRequestException;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PermissionsResourceTest {

    private Storage storage;
    private PermissionsService permissions;
    private TestResource resource;

    private static class TestResource extends PermissionsResource {
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
        when(permissions.notAdmin(1)).thenReturn(true);
        resource = new TestResource(storage, permissions);
    }

    private LinkedHashMap<String, Long> link(String owner, long ownerId, String property, long propertyId) {
        var result = new LinkedHashMap<String, Long>();
        result.put(owner, ownerId);
        result.put(property, propertyId);
        return result;
    }

    @Test
    public void reminderPermissionResolvesToRegisteredModel() {
        var permission = new Permission(link("deviceId", 1, "distanceReminderId", 2));
        assertEquals(Device.class, permission.getOwnerClass());
        assertEquals(DistanceReminder.class, permission.getPropertyClass());
        assertEquals("tc_device_distanceReminder", permission.getStorageName());
    }

    @Test
    public void forbiddenItemPreventsAllBulkWrites() throws Exception {
        doThrow(new SecurityException("Other operator"))
                .when(permissions).checkPermission(Device.class, 1, 20);
        var links = List.of(link("deviceId", 10, "distanceReminderId", 1),
                link("deviceId", 20, "distanceReminderId", 2));
        assertThrows(SecurityException.class, () -> resource.add(links));
        assertThrows(SecurityException.class, () -> resource.remove(links));
        verify(storage, never()).addPermission(any());
        verify(storage, never()).removePermission(any());
    }

    @Test
    public void cycleWithinSameBatchIsRejectedBeforeWrites() throws Exception {
        when(storage.getPermissions(Device.class, LinkedDevice.class)).thenReturn(List.of());
        var links = List.of(link("deviceId", 10, "linkedDeviceId", 20),
                link("deviceId", 20, "linkedDeviceId", 10));
        assertThrows(IllegalArgumentException.class, () -> resource.add(links));
        verify(storage, never()).addPermission(any());
    }

    @Test
    public void invalidPermissionTypesAndIdsAreRejectedBeforeWrites() throws Exception {
        assertThrows(BadRequestException.class,
                () -> resource.add(List.of(link("deviceId", 10, "unknownId", 1))));
        assertThrows(BadRequestException.class,
                () -> resource.remove(List.of(link("deviceId", 10, "distanceReminderId", 0))));
        verify(storage, never()).addPermission(any());
        verify(storage, never()).removePermission(any());
    }
}

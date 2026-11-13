package org.traccar.api.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseObjectResource;
import org.traccar.api.resource.DeviceResource;
import org.traccar.api.resource.PermissionsResource;
import org.traccar.broadcast.NullBroadcastService;
import org.traccar.config.Config;
import org.traccar.database.DeviceLookupService;
import org.traccar.database.NotificationManager;
import org.traccar.helper.LogAction;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.reports.RouteReportProvider;
import org.traccar.reports.common.ReportUtils;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.DatabaseFixture;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class OperatorIsolationTest {

    private DatabaseFixture fixture;
    private Storage storage;
    private ConnectionManager connections;
    private Position firstPosition;

    private static class TestPermissions extends PermissionsResource {
        TestPermissions(Storage storage, long actor) {
            this.storage = storage;
            this.permissionsService = new PermissionsService(storage);
            this.actor = actor;
        }

        private final long actor;

        @Override
        protected long getUserId() {
            return actor;
        }
    }

    private static class TestDevice extends DeviceResource {
        TestDevice(Storage storage) {
            this.storage = storage;
            this.permissionsService = new PermissionsService(storage);
        }

        @Override
        protected long getUserId() {
            return 1;
        }
    }

    private static void inject(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    public void setup() throws Exception {
        fixture = new DatabaseFixture();
        storage = fixture.getStorage();
        fixture.sql("INSERT INTO tc_users (id,name,email,attributes,administrator,userlimit) VALUES "
                + "(1,'Root','root@example.invalid','{}',TRUE,0),"
                + "(2,'Operator A','a@example.invalid','{}',FALSE,10),"
                + "(3,'Operator B','b@example.invalid','{}',FALSE,10),"
                + "(4,'Client A','ca@example.invalid','{}',FALSE,0),"
                + "(5,'Client B','cb@example.invalid','{}',FALSE,0)");
        fixture.sql("INSERT INTO tc_user_user VALUES (2,4),(3,5)");
        fixture.sql("INSERT INTO tc_groups (id,name,attributes) VALUES (1,'A','{}'),(2,'B','{}')");
        fixture.sql("INSERT INTO tc_user_group VALUES (2,1),(3,2)");
        fixture.sql("INSERT INTO tc_devices (id,name,uniqueid,groupid,attributes) "
                + "VALUES (1,'A','tracker-a',1,'{}'),(2,'B','tracker-b',2,'{}')");
        fixture.sql("INSERT INTO tc_user_device VALUES (2,1),(3,2),(4,1),(5,2)");
        for (int id = 1; id <= 2; id++) {
            Position position = new Position();
            position.setDeviceId(id);
            position.setDeviceTime(new Date());
            position.setFixTime(new Date());
            position.setServerTime(new Date());
            position.setValid(true);
            position.setId(storage.addObject(position, new Request(new Columns.Exclude("id"))));
            fixture.sql("UPDATE tc_devices SET positionid=" + position.getId() + " WHERE id=" + id);
            if (id == 1) {
                firstPosition = position;
            }
        }
        connections = new ConnectionManager(new Config(), mock(CacheManager.class), storage,
                mock(NotificationManager.class), new NullBroadcastService(), mock(DeviceLookupService.class));
    }

    @AfterEach
    public void cleanup() throws Exception {
        fixture.close();
    }

    private TestPermissions permissions() throws Exception {
        var resource = new TestPermissions(storage, 2);
        inject(resource, PermissionsResource.class, "cacheManager", mock(CacheManager.class));
        inject(resource, PermissionsResource.class, "connectionManager", connections);
        inject(resource, PermissionsResource.class, "actionLogger", mock(LogAction.class));
        return resource;
    }

    private LinkedHashMap<String, Long> link(String property, long value) {
        var map = new LinkedHashMap<String, Long>();
        map.put("userId", 4L);
        map.put(property, value);
        return map;
    }

    @Test
    public void managerOnlyManagesTheirOwnClientAndDevice() throws Exception {
        var permissions = new PermissionsService(storage);
        permissions.checkUser(2, 4);
        permissions.checkPermission(Device.class, 2, 1);
        assertThrows(SecurityException.class, () -> permissions.checkUser(2, 5));
        assertThrows(SecurityException.class, () -> permissions.checkUser(2, 3));
        assertThrows(SecurityException.class, () -> permissions.checkPermission(Device.class, 2, 2));
        var before = new User();
        var promoted = new User();
        promoted.setAdministrator(true);
        assertThrows(SecurityException.class, () -> permissions.checkUserUpdate(2, before, promoted));
        new PermissionsService(storage).checkPermission(Device.class, 1, 2);
    }

    @Test
    public void positionsAndReportQueriesCannotReturnOtherOperatorsData() throws Exception {
        assertEquals(List.of(1L), PositionUtil.getLatestPositions(storage, 2).stream().map(Position::getDeviceId).toList());
        var report = new RouteReportProvider(new Config(), mock(ReportUtils.class), storage);
        try (var positions = report.getObjects(2, List.of(2L), List.of(), new Date(0), new Date(Long.MAX_VALUE))) {
            assertTrue(positions.findAny().isEmpty());
        }
        var scoped = storage.getObjects(Position.class,
                new Request(new Columns.All(), new Condition.LatestPositions(0, 2)));
        assertEquals(List.of(1L), scoped.stream().map(Position::getDeviceId).toList());
    }

    @Test
    public void removingDirectAccessRevokesOpenSocketWithoutReconnect() throws Exception {
        var listener = mock(ConnectionManager.UpdateListener.class);
        connections.addListener(4, listener);
        connections.updatePosition(false, firstPosition);
        permissions().remove(link("deviceId", 1));
        connections.updatePosition(false, firstPosition);
        verify(listener, times(1)).onUpdatePosition(any());
    }

    @Test
    public void groupAccessSurvivesRemovalOfRedundantDirectLink() throws Exception {
        storage.addPermission(new Permission(User.class, 4, Group.class, 1));
        var listener = mock(ConnectionManager.UpdateListener.class);
        connections.addListener(4, listener);
        connections.updatePosition(false, firstPosition);
        permissions().remove(link("deviceId", 1));
        connections.updatePosition(false, firstPosition);
        verify(listener, times(2)).onUpdatePosition(any());
    }

    @Test
    public void removingGroupAccessRevokesOpenSocket() throws Exception {
        storage.removePermission(new Permission(User.class, 4, Device.class, 1));
        storage.addPermission(new Permission(User.class, 4, Group.class, 1));
        var listener = mock(ConnectionManager.UpdateListener.class);
        connections.addListener(4, listener);
        connections.updatePosition(false, firstPosition);
        permissions().remove(link("groupId", 1));
        connections.updatePosition(false, firstPosition);
        verify(listener, times(1)).onUpdatePosition(any());
    }

    @Test
    public void movingDeviceBetweenGroupsUpdatesBothUsersOpenSockets() throws Exception {
        storage.removePermission(new Permission(User.class, 4, Device.class, 1));
        storage.addPermission(new Permission(User.class, 4, Group.class, 1));
        storage.addPermission(new Permission(User.class, 5, Group.class, 2));
        var first = mock(ConnectionManager.UpdateListener.class);
        var second = mock(ConnectionManager.UpdateListener.class);
        connections.addListener(4, first);
        connections.addListener(5, second);
        connections.updatePosition(false, firstPosition);
        var resource = new TestDevice(storage);
        inject(resource, BaseObjectResource.class, "cacheManager", mock(CacheManager.class));
        inject(resource, BaseObjectResource.class, "connectionManager", connections);
        inject(resource, BaseObjectResource.class, "actionLogger", mock(LogAction.class));
        Device device = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("id", 1)));
        device.setGroupId(2);
        resource.update(device);
        connections.updatePosition(false, firstPosition);
        verify(first, times(1)).onUpdatePosition(any());
        verify(second, times(1)).onUpdatePosition(any());
    }
}

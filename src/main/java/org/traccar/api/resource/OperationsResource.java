package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.health.DeviceHealthService;
import org.traccar.helper.model.DeviceDiagnostics;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Path("operations")
@Produces(MediaType.APPLICATION_JSON)
public class OperationsResource extends BaseResource {

    @Inject
    private DeviceHealthService health;

    public record DeviceView(long id, String name, boolean monitoringEnabled,
            DeviceDiagnostics.Snapshot diagnostics, List<String> activeIncidents) {
    }

    @GET
    @Path("health")
    public List<DeviceView> get(@DefaultValue("50") @QueryParam("limit") int limit,
            @DefaultValue("0") @QueryParam("offset") int offset, @QueryParam("keyword") String keyword)
            throws StorageException {
        List<Condition> conditions = new ArrayList<>();
        if (permissionsService.notAdmin(getUserId())) {
            conditions.add(new Condition.Permission(User.class, getUserId(), Device.class));
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add(new Condition.Contains(List.of("name", "uniqueId"), keyword));
        }
        var devices = storage.getObjects(Device.class, new Request(new Columns.All(), Condition.merge(conditions),
                new Order("id", false, Math.max(1, Math.min(limit, 100)), Math.max(0, offset))));
        List<DeviceView> result = new ArrayList<>();
        Date now = new Date();
        for (Device device : devices) {
            Position position = storage.getObject(Position.class,
                    new Request(new Columns.All(), new Condition.LatestPositions(device.getId())));
            var active = health.activeKinds(device.getId());
            result.add(new DeviceView(device.getId(), device.getName(), device.getBoolean("monitoring.enabled"),
                    DeviceDiagnostics.describe(device, position, now, 900), active));
        }
        return result;
    }

    @GET
    @Path("devices/{id}/incidents")
    public List<DeviceHealthService.Incident> incidents(@PathParam("id") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return health.incidents(deviceId);
    }
}

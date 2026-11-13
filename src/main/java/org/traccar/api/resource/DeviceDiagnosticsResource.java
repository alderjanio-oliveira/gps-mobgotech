package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.helper.model.DeviceDiagnostics;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Date;

@Path("devices/{id}/diagnostics")
@Produces(MediaType.APPLICATION_JSON)
public class DeviceDiagnosticsResource extends BaseResource {

    @GET
    public DeviceDiagnostics.Snapshot get(
            @PathParam("id") long id,
            @DefaultValue("900") @QueryParam("staleSeconds") long staleSeconds) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), id);
        if (staleSeconds <= 0) {
            throw new BadRequestException("Stale threshold must be positive");
        }
        Device device = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("id", id)));
        if (device == null) {
            throw new NotFoundException();
        }
        Position position = device.getPositionId() > 0 ? storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.And(new Condition.Equals("id", device.getPositionId()),
                        new Condition.Equals("deviceId", id)))) : null;
        return DeviceDiagnostics.describe(device, position, new Date(), staleSeconds);
    }
}

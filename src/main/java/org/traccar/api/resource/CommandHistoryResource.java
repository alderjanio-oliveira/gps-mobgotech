package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.command.CommandLedger;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("devices/{deviceId}/command-history")
@Produces(MediaType.APPLICATION_JSON)
public class CommandHistoryResource extends BaseResource {

    @Inject
    private CommandLedger ledger;

    @GET
    public List<CommandLedger.RequestView> requests(@PathParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return ledger.requests(deviceId);
    }

    @GET
    @Path("responses")
    public List<CommandLedger.ResponseView> responses(@PathParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return ledger.responses(deviceId);
    }
}

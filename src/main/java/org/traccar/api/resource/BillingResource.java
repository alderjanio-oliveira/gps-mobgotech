package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.billing.BillingService;
import org.traccar.model.ObjectOperation;
import org.traccar.model.User;
import org.traccar.model.UserRestrictions;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.DateTimeException;
import java.util.List;

@Path("users/{userId}/billing")
@Produces(MediaType.APPLICATION_JSON)
public class BillingResource extends BaseResource {

    @Inject
    private BillingService billing;

    @Inject
    private CacheManager cacheManager;

    private void read(long userId) throws StorageException {
        permissionsService.checkUser(getUserId(), userId);
    }

    private void edit(long userId) throws StorageException {
        read(userId);
        permissionsService.checkManager(getUserId());
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);
        if (userId == getUserId()) {
            throw new SecurityException("Billing must be managed by another operator");
        }
    }

    @GET
    public Response get(@PathParam("userId") long userId) throws StorageException {
        read(userId);
        var account = billing.get(userId);
        return account == null ? Response.noContent().build() : Response.ok(account).build();
    }

    @PUT
    public BillingService.Account save(@PathParam("userId") long userId, BillingService.AccountInput input)
            throws Exception {
        edit(userId);
        if (input == null || input.dueDate() == null) {
            throw new BadRequestException("Due date is required");
        }
        try {
            var account = billing.save(userId, getUserId(), input);
            cacheManager.invalidateObject(true, User.class, userId, ObjectOperation.UPDATE);
            return account;
        } catch (IllegalArgumentException | DateTimeException error) {
            throw new BadRequestException(error.getMessage());
        } catch (BillingService.Conflict error) {
            throw new WebApplicationException(error.getMessage(), Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("payments")
    public List<BillingService.Payment> payments(@PathParam("userId") long userId) throws StorageException {
        read(userId);
        return billing.payments(userId);
    }

    @POST
    @Path("payments")
    public BillingService.Payment confirm(@PathParam("userId") long userId, BillingService.PaymentInput input)
            throws Exception {
        edit(userId);
        if (input == null || input.nextDueDate() == null) {
            throw new BadRequestException("Next due date is required");
        }
        try {
            return billing.confirm(userId, getUserId(), input);
        } catch (IllegalArgumentException | DateTimeException error) {
            throw new BadRequestException(error.getMessage());
        } catch (BillingService.Conflict error) {
            throw new WebApplicationException(error.getMessage(), Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("changes")
    public List<BillingService.Change> changes(@PathParam("userId") long userId) throws StorageException {
        read(userId);
        permissionsService.checkManager(getUserId());
        return billing.changes(userId);
    }
}

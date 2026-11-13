/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import org.traccar.api.ExtendedObjectResource;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.DistanceReminder;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Date;
import java.util.List;

@Path("distancereminders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DistanceReminderResource extends ExtendedObjectResource<DistanceReminder> {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public DistanceReminderResource() {
        super(DistanceReminder.class, "name", List.of("name"));
    }

    private void validate(DistanceReminder reminder) {
        if (reminder == null || reminder.getName() == null || reminder.getName().isBlank()
                || !Double.isFinite(reminder.getThresholdDistance()) || reminder.getThresholdDistance() <= 0
                || !Double.isFinite(reminder.getStartValue()) || reminder.getStartValue() < 0) {
            throw new BadRequestException("Name, positive threshold and non-negative start value are required");
        }
    }

    @Override
    @POST
    public Response add(DistanceReminder reminder) throws Exception {
        validate(reminder);
        reminder.setStatus(DistanceReminder.STATUS_PENDING);
        reminder.setNotifiedAt(null);
        reminder.setConfirmedAt(null);
        reminder.setCancelledAt(null);
        reminder.setTraveledDistance(0);
        return super.add(reminder);
    }

    @Override
    @Path("{id}")
    @PUT
    public Response update(DistanceReminder reminder) throws Exception {
        validate(reminder);
        permissionsService.checkPermission(DistanceReminder.class, getUserId(), reminder.getId());
        DistanceReminder before = storage.getObject(DistanceReminder.class, new Request(
                new Columns.All(), new Condition.Equals("id", reminder.getId())));
        if (before == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!DistanceReminder.STATUS_PENDING.equals(before.getStatus())) {
            return Response.status(Response.Status.CONFLICT).build();
        }
        reminder.setStatus(before.getStatus());
        reminder.setNotifiedAt(before.getNotifiedAt());
        reminder.setConfirmedAt(before.getConfirmedAt());
        reminder.setCancelledAt(before.getCancelledAt());
        reminder.setTraveledDistance(before.getTraveledDistance());
        return super.update(reminder);
    }

    @Path("{id}/confirm")
    @POST
    public Response confirm(@PathParam("id") long id) throws Exception {
        permissionsService.checkPermission(DistanceReminder.class, getUserId(), id);
        permissionsService.checkEdit(getUserId(), DistanceReminder.class, false, false);

        DistanceReminder reminder = storage.getObject(DistanceReminder.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (reminder == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (DistanceReminder.STATUS_DONE.equals(reminder.getStatus())) {
            return Response.ok(reminder).build();
        }
        if (DistanceReminder.STATUS_CANCELLED.equals(reminder.getStatus())) {
            return Response.status(Response.Status.CONFLICT).build();
        }
        double totalDistance = findCurrentTotalDistance(id, reminder.getStartValue());

        reminder.setStatus(DistanceReminder.STATUS_DONE);
        reminder.setConfirmedAt(new Date());
        reminder.setTraveledDistance(totalDistance - reminder.getStartValue());

        storage.updateObject(reminder, new Request(
                new Columns.Include("status", "confirmedAt", "traveledDistance"),
                new Condition.Equals("id", id)));
        cacheManager.invalidateObject(true, DistanceReminder.class, id, ObjectOperation.UPDATE);
        actionLogger.edit(request, getUserId(), reminder);

        return Response.ok(reminder).build();
    }

    @Path("{id}/cancel")
    @POST
    public Response cancel(@PathParam("id") long id) throws Exception {
        permissionsService.checkPermission(DistanceReminder.class, getUserId(), id);
        permissionsService.checkEdit(getUserId(), DistanceReminder.class, false, false);

        DistanceReminder reminder = storage.getObject(DistanceReminder.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (reminder == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (DistanceReminder.STATUS_CANCELLED.equals(reminder.getStatus())) {
            return Response.ok(reminder).build();
        }
        if (DistanceReminder.STATUS_DONE.equals(reminder.getStatus())) {
            return Response.status(Response.Status.CONFLICT).build();
        }
        reminder.setStatus(DistanceReminder.STATUS_CANCELLED);
        reminder.setCancelledAt(new Date());

        storage.updateObject(reminder, new Request(
                new Columns.Include("status", "cancelledAt"),
                new Condition.Equals("id", id)));
        cacheManager.invalidateObject(true, DistanceReminder.class, id, ObjectOperation.UPDATE);
        actionLogger.edit(request, getUserId(), reminder);

        return Response.ok(reminder).build();
    }

    private double findCurrentTotalDistance(long reminderId, double fallback) throws StorageException {
        List<Permission> links = storage.getPermissions(Device.class, 0, DistanceReminder.class, reminderId);
        if (links.isEmpty()) {
            return fallback;
        }
        long deviceId = links.get(0).getOwnerId();

        Position position = cacheManager.getPosition(deviceId);
        if (position == null) {
            Device device = storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", deviceId)));
            if (device != null && device.getPositionId() > 0) {
                position = storage.getObject(Position.class, new Request(
                        new Columns.All(), new Condition.Equals("id", device.getPositionId())));
            }
        }
        return position != null ? position.getDouble(Position.KEY_TOTAL_DISTANCE) : fallback;
    }

}

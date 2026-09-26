/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2017 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Singleton
public class BlockStateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockStateManager.class);

    private final Storage storage;
    private final CacheManager cacheManager;

    @Inject
    public BlockStateManager(Storage storage, CacheManager cacheManager) {
        this.storage = storage;
        this.cacheManager = cacheManager;
    }

    public String request(Command command) throws StorageException {
        String state = switch (command.getType()) {
            case Command.TYPE_ENGINE_STOP -> "blocked";
            case Command.TYPE_ENGINE_RESUME -> "unblocked";
            default -> null;
        };
        if (state == null) {
            return null;
        }
        Device device = new Device();
        device.setId(command.getDeviceId());
        device.setBlockRequestedState(state);
        device.setBlockRequestedTime(new Date(System.currentTimeMillis() / 1000 * 1000));
        device.setBlockRequestStatus("pending");
        device.setBlockRequestId(UUID.randomUUID().toString());
        storage.updateObject(device, new Request(
                new Columns.Include(
                        "blockRequestedState", "blockRequestedTime", "blockRequestStatus", "blockRequestId"),
                new Condition.Equals("id", device.getId())));
        invalidate(device.getId());
        return device.getBlockRequestId();
    }

    public void failed(long deviceId, String requestId) throws StorageException {
        if (requestId == null) {
            return;
        }
        Device device = new Device();
        device.setBlockRequestStatus("failed");
        storage.updateObject(device, new Request(new Columns.Include("blockRequestStatus"),
                new Condition.And(new Condition.Equals("id", deviceId),
                        new Condition.Equals("blockRequestId", requestId))));
        invalidate(deviceId);
    }

    public void report(Position position) throws StorageException {
        Object value = position.getAttributes().get(Position.KEY_BLOCKED);
        Boolean blocked = parseBlocked(value);
        if (blocked == null || position.getBoolean(Position.KEY_ARCHIVE)) {
            return;
        }
        Date time = position.getDeviceTime() != null ? position.getDeviceTime() : position.getServerTime();
        if (time == null || position.getServerTime() == null || time.after(position.getServerTime())) {
            return;
        }
        // Use second precision consistently with MySQL TIMESTAMP columns.
        time = new Date(time.getTime() / 1000 * 1000);
        Device device = new Device();
        device.setBlockState(blocked ? "blocked" : "unblocked");
        device.setBlockStateTime(time);
        storage.updateObject(device, new Request(new Columns.Include("blockState", "blockStateTime"),
                new Condition.And(new Condition.Equals("id", position.getDeviceId()),
                        new Condition.Or(new Condition.Equals("blockState", "unknown"),
                                new Condition.Compare("blockStateTime", "<", time)))));
        device.setBlockRequestStatus("confirmed");
        storage.updateObject(device, new Request(new Columns.Include("blockRequestStatus"),
                Condition.merge(List.of(
                        new Condition.Equals("id", position.getDeviceId()),
                        new Condition.Equals("blockState", device.getBlockState()),
                        new Condition.Equals("blockStateTime", time),
                        new Condition.Equals("blockRequestedState", device.getBlockState()),
                        new Condition.Compare("blockRequestedTime", "<", time),
                        new Condition.Equals("blockRequestStatus", "pending")))));
        invalidate(position.getDeviceId());
    }

    private static Boolean parseBlocked(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            if (number.doubleValue() == 1) {
                return true;
            } else if (number.doubleValue() == 0) {
                return false;
            }
        }
        return null;
    }

    private void invalidate(long deviceId) {
        try {
            cacheManager.invalidateObject(true, Device.class, deviceId, ObjectOperation.UPDATE);
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh device block state", e);
        }
    }

}

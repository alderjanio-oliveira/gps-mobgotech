/*
 * Copyright 2016 - 2024 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

public class ChargeEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChargeEventHandler.class);
    private static final String KEY_CHARGE_STATE = "chargeState";

    private final CacheManager cacheManager;
    private final Storage storage;

    @Inject
    public ChargeEventHandler(CacheManager cacheManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.storage = storage;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null || !PositionUtil.isLatest(cacheManager, position)) {
            return;
        }

        if (!position.hasAttribute(Position.KEY_CHARGE)) {
            return;
        }
        boolean charge = position.getBoolean(Position.KEY_CHARGE);

        // o atributo charge nem sempre vem em toda posição (depende do protocolo do
        // dispositivo) — comparar só com a última posição recebida (que pode não ter
        // esse atributo) perde a transição real. Por isso o último valor conhecido fica
        // guardado no próprio device, sobrevivendo a posições sem o atributo no meio.
        if (device.hasAttribute(KEY_CHARGE_STATE)) {
            boolean oldCharge = device.getBoolean(KEY_CHARGE_STATE);
            if (charge && !oldCharge) {
                callback.eventDetected(new Event(Event.TYPE_CHARGE_CONNECTED, position));
            } else if (!charge && oldCharge) {
                callback.eventDetected(new Event(Event.TYPE_CHARGE_DISCONNECTED, position));
            }
        }

        if (!device.hasAttribute(KEY_CHARGE_STATE) || device.getBoolean(KEY_CHARGE_STATE) != charge) {
            device.set(KEY_CHARGE_STATE, charge);
            try {
                storage.updateObject(device, new Request(
                        new Columns.Include("attributes"),
                        new Condition.Equals("id", device.getId())));
            } catch (StorageException e) {
                LOGGER.warn("Update device charge state error", e);
            }
        }
    }

}

package org.traccar.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.command.CommandLedger;
import org.traccar.model.Position;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;

public class CommandLedgerHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandLedgerHandler.class);
    private final CommandLedger ledger;

    @Inject
    public CommandLedgerHandler(CommandLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (position.hasAttribute(Position.KEY_RESULT) || position.hasAttribute(Position.KEY_BLOCKED)) {
            try {
                ledger.observe(position);
            } catch (StorageException error) {
                LOGGER.warn("Command evidence persistence failed: device={}", position.getDeviceId());
            }
        }
        callback.processed(false);
    }
}

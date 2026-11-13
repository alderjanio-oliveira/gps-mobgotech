package org.traccar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.LoginService;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskUserAccessCheck implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskUserAccessCheck.class);
    private final LoginService loginService;
    private final ConnectionManager connectionManager;

    @Inject
    public TaskUserAccessCheck(LoginService loginService, ConnectionManager connectionManager) {
        this.loginService = loginService;
        this.connectionManager = connectionManager;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleWithFixedDelay(this, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        for (long userId : connectionManager.listenerUserIds()) {
            try {
                loginService.checkUserAccess(userId);
            } catch (SecurityException error) {
                connectionManager.revokeUserAccess(userId);
            } catch (StorageException error) {
                LOGGER.warn("User access check unavailable: user={}", userId);
            }
        }
    }
}

package org.traccar.schedule;

import org.junit.jupiter.api.Test;
import org.traccar.api.security.LoginService;
import org.traccar.session.ConnectionManager;

import java.util.Set;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TaskUserAccessCheckTest {

    @Test
    public void onlyRevokedUserSocketsAreDisconnected() throws Exception {
        var login = mock(LoginService.class);
        var connections = mock(ConnectionManager.class);
        when(connections.listenerUserIds()).thenReturn(Set.of(1L, 2L));
        doThrow(new SecurityException("Suspended")).when(login).checkUserAccess(2);
        new TaskUserAccessCheck(login, connections).run();
        verify(connections).revokeUserAccess(2);
        verify(connections, never()).revokeUserAccess(1);
    }
}

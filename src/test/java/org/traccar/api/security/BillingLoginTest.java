package org.traccar.api.security;

import org.junit.jupiter.api.Test;
import org.traccar.api.signature.TokenManager;
import org.traccar.billing.BillingService;
import org.traccar.config.Config;
import org.traccar.model.User;
import org.traccar.storage.Storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class BillingLoginTest {

    @Test
    public void financialSuspensionAppliesToExistingUserAccess() throws Exception {
        var storage = mock(Storage.class);
        User user = new User();
        user.setId(2);
        when(storage.getObject(eq(User.class), any())).thenReturn(user);
        var billing = mock(BillingService.class);
        doThrow(new SecurityException("Suspended")).when(billing).checkAccess(2);
        var login = new LoginService(new Config(), storage, mock(TokenManager.class), null,
                () -> mock(PermissionsService.class), billing);
        assertThrows(SecurityException.class, () -> login.checkUserAccess(2));
    }

    @Test
    public void paymentDoesNotBypassAdministrativeDisable() throws Exception {
        var storage = mock(Storage.class);
        User user = new User();
        user.setId(2);
        user.setDisabled(true);
        when(storage.getObject(eq(User.class), any())).thenReturn(user);
        var billing = mock(BillingService.class);
        var login = new LoginService(new Config(), storage, mock(TokenManager.class), null,
                () -> mock(PermissionsService.class), billing);
        assertThrows(SecurityException.class, () -> login.checkUserAccess(2));
        verifyNoInteractions(billing);
    }

    @Test
    public void globalAdministratorAndServiceAccountKeepTheirAccess() throws Exception {
        var storage = mock(Storage.class);
        User user = new User();
        user.setId(1);
        user.setAdministrator(true);
        when(storage.getObject(eq(User.class), any())).thenReturn(user);
        var billing = mock(BillingService.class);
        var login = new LoginService(new Config(), storage, mock(TokenManager.class), null,
                () -> mock(PermissionsService.class), billing);
        login.checkUserAccess(1);
        login.checkUserAccess(ServiceAccountUser.ID);
        verifyNoInteractions(billing);
    }
}

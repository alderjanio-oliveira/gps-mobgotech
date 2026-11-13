package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import org.traccar.api.security.PermissionsService;
import org.traccar.billing.BillingService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

public class BillingResourceTest {

    private static class TestResource extends BillingResource {
        private final long actor;

        TestResource(long actor, PermissionsService permissions) {
            this.actor = actor;
            this.permissionsService = permissions;
        }

        @Override
        protected long getUserId() {
            return actor;
        }
    }

    @Test
    public void userCannotChangeTheirOwnFinancialAccess() {
        var resource = new TestResource(2, mock(PermissionsService.class));
        assertThrows(SecurityException.class, () -> resource.save(2,
                new BillingService.AccountInput("2030-01-01", 10, "America/Manaus", 0, true)));
        assertThrows(SecurityException.class, () -> resource.confirm(2, null));
    }

    @Test
    public void foreignClientIsDeniedBeforeReadingOrWriting() throws Exception {
        var permissions = mock(PermissionsService.class);
        doThrow(new SecurityException("Foreign client")).when(permissions).checkUser(1, 2);
        var resource = new TestResource(1, permissions);
        assertThrows(SecurityException.class, () -> resource.get(2));
        assertThrows(SecurityException.class, () -> resource.payments(2));
        assertThrows(SecurityException.class, () -> resource.confirm(2, null));
    }

    @Test
    public void readonlyOperatorCannotConfirmPayment() throws Exception {
        var permissions = mock(PermissionsService.class);
        doThrow(new SecurityException("Readonly")).when(permissions).checkRestriction(eq(1L), any());
        var resource = new TestResource(1, permissions);
        assertThrows(SecurityException.class, () -> resource.confirm(2, null));
    }
}

package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.SaaAdminAccount;
import io.openware.platform.admin.domain.port.SaaAdminAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AdminPasswordApplicationServiceTest {
    private SaaAdminAccountRepository accounts;
    private BCryptPasswordEncoder passwords;
    private AdminPasswordApplicationService service;
    private String oldHash;

    @BeforeEach
    void setUp() {
        accounts = mock(SaaAdminAccountRepository.class);
        passwords = new BCryptPasswordEncoder();
        service = new AdminPasswordApplicationService(accounts, passwords);
        oldHash = passwords.encode("OldPass123");
        when(accounts.findById(1L)).thenReturn(Optional.of(new SaaAdminAccount(1L, "admin", oldHash,
                "Admin", null, AdminRole.SUPER_ADMIN, SaaAdminAccount.STATUS_ACTIVE, LocalDateTime.now(), LocalDateTime.now())));
        when(accounts.updatePassword(eq(1L), anyString())).thenReturn(1);
    }

    @Test
    void changesPasswordOnlyForAuthenticatedAccount() {
        service.changePassword(1L, "OldPass123", "NewPass123");
        verify(accounts).updatePassword(eq(1L), argThat(hash -> passwords.matches("NewPass123", hash)));
    }

    @Test
    void rejectsWrongCurrentPasswordAndInvalidNewPassword() {
        assertThrows(ApiException.class, () -> service.changePassword(1L, "bad", "NewPass123"));
        assertThrows(ApiException.class, () -> service.changePassword(1L, "OldPass123", "short"));
        verify(accounts, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    void rejectsSamePasswordAndOversizedUtf8Password() {
        assertThrows(ApiException.class, () -> service.changePassword(1L, "OldPass123", "OldPass123"));
        assertThrows(ApiException.class, () -> service.changePassword(1L, "OldPass123", "密".repeat(25)));
        assertTrue(passwords.matches("OldPass123", oldHash));
        verify(accounts, never()).updatePassword(anyLong(), anyString());
    }
}

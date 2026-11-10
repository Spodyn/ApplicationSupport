package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.unifiedsupportinbox.ApiProblemException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SessionAuthenticationServiceTests {
    @Test
    void unknownAndUninitializedAccountsStillPerformExpensiveVerification() {
        var users = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("dummy-hash");
        when(users.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());
        var service = new SessionAuthenticationService(users, encoder);
        assertThatThrownBy(() -> service.authenticate("unknown@example.com", "test-only-password"))
                .isInstanceOf(ApiProblemException.class);
        verify(encoder).matches("test-only-password", "dummy-hash");
        var uninitialized = mock(UserAccount.class);
        when(users.findByEmailIgnoreCase("invite@example.com")).thenReturn(Optional.of(uninitialized));
        assertThatThrownBy(() -> service.authenticate("invite@example.com", "test-only-invite"))
                .isInstanceOf(ApiProblemException.class);
        verify(encoder).matches("test-only-invite", "dummy-hash");
    }
}

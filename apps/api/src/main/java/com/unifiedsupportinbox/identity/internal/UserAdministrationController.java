package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.identity.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/v1")
class UserAdministrationController {

    private final UserAdministrationService service;
    private final AuthenticationAbuseGuard abuse;

    UserAdministrationController(UserAdministrationService service, AuthenticationAbuseGuard abuse) {
        this.service = service;
        this.abuse = abuse;
    }

    @GetMapping("/admin/users/current")
    UserAdministrationService.UserView current(Authentication authentication) {
        return service.current(principal(authentication));
    }

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserAdministrationService.UserView create(@Valid @RequestBody UserRequest input, Authentication authentication) {
        return service.create(principal(authentication), input.toInput());
    }

    @PutMapping("/admin/users/{userId}")
    UserAdministrationService.UserView update(@PathVariable UUID userId, @Valid @RequestBody UserRequest input,
            Authentication authentication) {
        return service.update(principal(authentication), userId, input.toInput());
    }

    @PatchMapping("/admin/users/{userId}/deactivate")
    UserAdministrationService.UserView deactivate(@PathVariable UUID userId, Authentication authentication) {
        return service.deactivate(principal(authentication), userId);
    }

    @DeleteMapping("/admin/users/{userId}")
    ResponseEntity<Void> delete(@PathVariable UUID userId, Authentication authentication) {
        service.delete(principal(authentication), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/users/{userId}/invitation")
    UserAdministrationService.CredentialToken invitation(@PathVariable UUID userId, Authentication authentication) {
        return service.issueInvitation(principal(authentication), userId);
    }

    @PostMapping("/admin/users/{userId}/password-reset")
    UserAdministrationService.CredentialToken reset(@PathVariable UUID userId, Authentication authentication) {
        return service.issuePasswordReset(principal(authentication), userId);
    }

    @PostMapping("/auth/password-reset/request")
    ResponseEntity<Void> requestReset(@Valid @RequestBody ResetRequest input, HttpServletRequest request) {
        // Keep the external response neutral even when a reset is suppressed.
        if (abuse.reset(input.email(), request.getRemoteAddr()) == 0) service.requestPasswordReset(input.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/auth/invitations/accept")
    ResponseEntity<Void> acceptInvitation(@Valid @RequestBody CredentialRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        guardActionToken(request, response);
        service.consumeToken(input.token(), input.password(), "INVITATION");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/password-reset/complete")
    ResponseEntity<Void> completeReset(@Valid @RequestBody CredentialRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        guardActionToken(request, response);
        service.consumeToken(input.token(), input.password(), "PASSWORD_RESET");
        return ResponseEntity.noContent().build();
    }

    private static UsiSessionPrincipal principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UsiSessionPrincipal principal)) {
            throw ApiProblemException.authenticationRequired();
        }
        return principal;
    }

    private void guardActionToken(HttpServletRequest request, HttpServletResponse response) {
        long retryAfter = abuse.actionToken(request.getRemoteAddr());
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            throw ApiProblemException.rateLimited("Too many authentication attempts. Try again later.");
        }
    }

    record UserRequest(@NotBlank String fullName, @Email @NotBlank String email, @NotNull UserRole role,
                       boolean active, Instant validFrom, Instant validUntil, @NotNull List<String> permissions) {
        UserAdministrationService.UserInput toInput() {
            return new UserAdministrationService.UserInput(fullName, email, role, active, validFrom, validUntil, permissions);
        }
    }
    record ResetRequest(@Email @NotBlank String email) { }
    record CredentialRequest(@NotBlank String token, @NotBlank String password) { }
}

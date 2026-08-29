package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final SessionAuthenticationService authentication;
    private final SecurityContextRepository securityContexts;
    private final PermissionService permissions;

    AuthController(
            SessionAuthenticationService authentication,
            SecurityContextRepository securityContexts,
            PermissionService permissions) {
        this.authentication = authentication;
        this.securityContexts = securityContexts;
        this.permissions = permissions;
    }

    @PostMapping("/login")
    ResponseEntity<CurrentSessionResponse> login(
            @Valid @RequestBody LoginRequest input,
            HttpServletRequest request,
            HttpServletResponse response) {
        UsiSessionPrincipal principal = authentication.authenticate(input.email(), input.password());
        List<String> effectivePermissions = permissions.effectivePermissions(principal.userId());

        HttpSession existingSession = request.getSession(false);
        if (existingSession == null) {
            request.getSession(true);
        } else {
            request.changeSessionId();
        }

        UsernamePasswordAuthenticationToken authenticated =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.authorities(effectivePermissions));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);

        return ResponseEntity.ok(CurrentSessionResponse.from(principal, effectivePermissions));
    }

    @GetMapping("/me")
    CurrentSessionResponse current(Authentication currentAuthentication) {
        if (currentAuthentication == null
                || !currentAuthentication.isAuthenticated()
                || !(currentAuthentication.getPrincipal() instanceof UsiSessionPrincipal principal)) {
            throw ApiProblemException.authenticationRequired();
        }
        return CurrentSessionResponse.from(
                principal,
                permissions.effectivePermissions(principal.userId()));
    }

    record LoginRequest(@NotNull String email, @NotNull String password) {
    }
}

package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.IdempotencyResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** ADMIN + manage_users only. The initial password must never be echoed. */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserCreateController {

    private final AdminUserCreateService users;

    AdminUserCreateController(AdminUserCreateService users) {
        this.users = users;
    }

    @PostMapping
    ResponseEntity<JsonNode> create(
            @RequestBody AdminUserCreateRequest request,
            @RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UsiSessionPrincipal actor)) {
            throw ApiProblemException.authenticationRequired();
        }
        IdempotencyResult created = users.create(actor, request, key);
        return ResponseEntity.status(created.status()).body(created.body());
    }
}

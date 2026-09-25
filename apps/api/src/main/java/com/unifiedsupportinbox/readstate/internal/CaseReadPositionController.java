package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.ApiProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
class CaseReadPositionController {

    private final CaseReadPositionService positions;

    CaseReadPositionController(CaseReadPositionService positions) {
        this.positions = positions;
    }

    @PutMapping("/{caseId}/read-position")
    ReadPositionResponse markRead(
            @PathVariable UUID caseId,
            @Valid @RequestBody ReadPositionRequest request,
            Authentication authentication) {
        CaseReadPositionService.ReadPosition position = positions.markRead(
                caseId, authenticatedUserId(authentication), request.messageId());
        return new ReadPositionResponse(position.caseId(), position.messageId(), position.readAt());
    }

    private static UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiProblemException.authenticationRequired();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.authenticationRequired();
        }
    }

    record ReadPositionRequest(@NotNull UUID messageId) {}
    record ReadPositionResponse(UUID caseId, UUID messageId, Instant readAt) {}
}

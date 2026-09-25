package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActors;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.sla.SlaPolicyView;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SlaPolicyService {
    private static final String MANAGE_SLA = "manage_sla";
    private final SlaPolicyRepository policies; private final AuditEventStore audit;
    SlaPolicyService(SlaPolicyRepository policies, AuditEventStore audit) { this.policies = policies; this.audit = audit; }
    @Transactional(readOnly = true) SlaPolicyView active(Authentication actor) { require(actor); return policies.active(); }
    @Transactional SlaPolicyView replace(Authentication actor, UpdateInput input) {
        require(actor); if (input == null || input.version() == null || input.version() < 1) throw ApiProblemException.validationFailed("SLA policy version must be positive.");
        String name = text(input.name()); validate(input);
        SlaPolicyView current = policies.active();
        SlaPolicyView updated = policies.update(current.id(), input.version(), name, input.firstResponseMinutes(), input.unclaimedWarningMinutes(), input.unclaimedBreachMinutes(), input.inProgressWarningMinutes(), input.inProgressBreachMinutes(), input.pauseWaiting()).orElseThrow(() -> ApiProblemException.conflict("SLA policy changed since it was loaded."));
        audit.append(AuditActors.type(actor), AuditActors.userId(actor), "SLA_POLICY_UPDATED", "SLA_POLICY", updated.id(), null, Map.of("version",updated.version(),"pauseWaiting",updated.pauseWaiting())); return updated;
    }
    private static void validate(UpdateInput v) { if (v.firstResponseMinutes()<1 || v.unclaimedWarningMinutes()<1 || v.inProgressWarningMinutes()<1 || v.unclaimedBreachMinutes()<v.unclaimedWarningMinutes() || v.inProgressBreachMinutes()<v.inProgressWarningMinutes()) throw ApiProblemException.validationFailed("SLA thresholds must be positive and breach thresholds must not precede warnings."); }
    private static String text(String value) { if (value == null || value.isBlank() || value.length()>160) throw ApiProblemException.validationFailed("SLA policy name is required."); return value.strip(); }
    private static void require(Authentication a) { if (a == null || !a.isAuthenticated() || a.getAuthorities().stream().noneMatch(x -> MANAGE_SLA.equals(x.getAuthority()))) throw ApiProblemException.accessDenied(); }
    record UpdateInput(String name, long firstResponseMinutes, long unclaimedWarningMinutes, long unclaimedBreachMinutes, long inProgressWarningMinutes, long inProgressBreachMinutes, boolean pauseWaiting, Long version) { }
}

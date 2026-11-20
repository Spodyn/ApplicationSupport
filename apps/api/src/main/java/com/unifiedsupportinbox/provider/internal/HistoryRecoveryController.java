package com.unifiedsupportinbox.provider.internal;

import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.audit.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/integrations/{integrationId}/resync")
class HistoryRecoveryController {
    private final HistoryRecoveryStore store;
    private final AuditEventStore audit;
    HistoryRecoveryController(HistoryRecoveryStore store, AuditEventStore audit) {this.store=store;this.audit=audit;}
    @GetMapping
    HistoryRecoveryStore.View get(@PathVariable UUID integrationId, Authentication actor) {
        authorize(actor);return store.view(integrationId);
    }
    @PutMapping @Transactional
    HistoryRecoveryStore.Settings save(@PathVariable UUID integrationId, @RequestBody HistoryRecoveryStore.Settings input, Authentication actor) {
        authorize(actor);var result=store.save(integrationId,input);
        audit.append(AuditActors.type(actor),AuditActors.userId(actor),"RESYNC_SETTINGS_CHANGED","integration",integrationId,null,
                Map.of("enabled",input.enabled(),"automatic",input.automatic(),"intervalMinutes",input.intervalMinutes(),"lookbackMinutes",input.lookbackMinutes()));
        return result;
    }
    record Start(String mode, Instant from) { }
    record Started(UUID id) { }
    @PostMapping @Transactional
    Started start(@PathVariable UUID integrationId,@RequestBody Start input,Authentication actor) {
        authorize(actor);
        if (!Set.of("from_date","all_history").contains(Objects.toString(input.mode(),""))
                || input.mode().equals("from_date") && input.from()==null)
            throw ApiProblemException.validationFailed("Choose from_date with a date or all_history.");
        UUID run=store.start(integrationId,input.mode().equals("all_history")?null:input.from(),"manual",Instant.now());
        audit.append(AuditActors.type(actor),AuditActors.userId(actor),"RESYNC_REQUESTED","integration",integrationId,null,Map.of("runId",run.toString(),"mode",input.mode()));
        return new Started(run);
    }
    private static void authorize(Authentication actor) {
        if(actor==null || actor.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN"))
                || actor.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("manage_integrations"))) throw ApiProblemException.accessDenied();
    }
}

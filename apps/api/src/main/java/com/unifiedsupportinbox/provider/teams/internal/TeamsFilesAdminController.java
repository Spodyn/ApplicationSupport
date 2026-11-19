package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.audit.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/integrations/{integrationId}/teams/attachments")
class TeamsFilesAdminController {
    private final TeamsInboundAttachments attachments;
    private final JdbcTemplate jdbc;
    private final AuditEventStore audit;
    TeamsFilesAdminController(TeamsInboundAttachments attachments,JdbcTemplate jdbc,AuditEventStore audit){this.attachments=attachments;this.jdbc=jdbc;this.audit=audit;}
    @PostMapping("/retry") @Transactional
    ResponseEntity<RetryResult> retry(@PathVariable UUID integrationId,Authentication actor) {
        if(actor==null||actor.getAuthorities().stream().noneMatch(a->"ROLE_ADMIN".equals(a.getAuthority()))
                ||actor.getAuthorities().stream().noneMatch(a->"manage_integrations".equals(a.getAuthority())))throw ApiProblemException.accessDenied();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM integrations WHERE id=? AND provider='TEAMS' AND status='ENABLED')",Boolean.class,integrationId)))
            throw ApiProblemException.notFound("Enabled Teams integration was not found.");
        int count=attachments.retry(integrationId);
        audit.append(AuditActors.type(actor),AuditActors.userId(actor),"TEAMS_ATTACHMENT_RETRY","integration",integrationId,null,Map.of("scheduled",count));
        return ResponseEntity.accepted().body(new RetryResult(count));
    }
    record RetryResult(int scheduled) { }
}

package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class TeamsFilesAdminControllerTests {
    final TeamsInboundAttachments jobs=mock(TeamsInboundAttachments.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final AuditEventStore audit=mock(AuditEventStore.class);
    final TeamsFilesAdminController controller=new TeamsFilesAdminController(jobs,jdbc,audit);
    final UUID integration=UUID.randomUUID(),user=UUID.randomUUID();
    UsernamePasswordAuthenticationToken actor(String... authorities) {
        return new UsernamePasswordAuthenticationToken(user.toString(),null,Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }
    @Test void requiresBothAdminRoleAndIntegrationCapability() {
        for(var actor:List.of(actor("ROLE_ADMIN"),actor("manage_integrations"),actor("ROLE_USER")))
            assertThatThrownBy(()->controller.retry(integration,actor)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(()->controller.retry(integration,null)).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(jobs,jdbc,audit);
    }
    @Test void wrongProviderDisabledOrMissingIntegrationCannotScheduleFiles() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(integration))).thenReturn(false);
        assertThatThrownBy(()->controller.retry(integration,actor("ROLE_ADMIN","manage_integrations"))).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(jobs,audit);
    }
    @Test void authorizedRetryIsScopedAndAudited() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(integration))).thenReturn(true);when(jobs.retry(integration)).thenReturn(3);
        var response=controller.retry(integration,actor("ROLE_ADMIN","manage_integrations"));
        assertThat(response.getStatusCode().value()).isEqualTo(202);assertThat(response.getBody().scheduled()).isEqualTo(3);
        verify(jobs).retry(integration);verify(audit).append(AuditActorType.ADMIN,user,"TEAMS_ATTACHMENT_RETRY","integration",integration,null,Map.of("scheduled",3));
    }
}

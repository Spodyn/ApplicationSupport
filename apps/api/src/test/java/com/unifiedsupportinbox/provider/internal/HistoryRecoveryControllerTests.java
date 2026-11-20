package com.unifiedsupportinbox.provider.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditEventStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class HistoryRecoveryControllerTests {
    HistoryRecoveryStore store=mock(HistoryRecoveryStore.class);
    HistoryRecoveryController controller=new HistoryRecoveryController(store,mock(AuditEventStore.class));
    UUID integration=UUID.randomUUID();
    UsernamePasswordAuthenticationToken actor(String... authorities){return new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(),"",Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());}
    @Test void allOperationsRequireAdminAndIntegrationPermission() {
        for(var actor:List.of(actor("ROLE_USER"),actor("ROLE_ADMIN"),actor("manage_integrations"))) {
            assertThatThrownBy(()->controller.get(integration,actor)).isInstanceOf(ApiProblemException.class);
            assertThatThrownBy(()->controller.save(integration,null,actor)).isInstanceOf(ApiProblemException.class);
            assertThatThrownBy(()->controller.start(integration,null,actor)).isInstanceOf(ApiProblemException.class);
        }
        verifyNoInteractions(store);
    }
    @Test void bothManualModesUseExplicitRangeAndRejectMissingOrFutureDate() {
        var admin=actor("ROLE_ADMIN","manage_integrations");Instant from=Instant.parse("2026-11-01T00:00:00Z");
        when(store.start(eq(integration),any(),eq("manual"),any())).thenReturn(UUID.randomUUID());
        controller.start(integration,new HistoryRecoveryController.Start("from_date",from),admin);
        verify(store).start(eq(integration),eq(from),eq("manual"),any());
        controller.start(integration,new HistoryRecoveryController.Start("all_history",from),admin);
        verify(store).start(eq(integration),isNull(),eq("manual"),any());
        assertThatThrownBy(()->controller.start(integration,new HistoryRecoveryController.Start("from_date",null),admin)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(()->controller.start(integration,new HistoryRecoveryController.Start("unknown",null),admin)).isInstanceOf(ApiProblemException.class);
    }
}

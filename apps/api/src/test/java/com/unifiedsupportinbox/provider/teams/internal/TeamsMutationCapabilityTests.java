package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.MutationTarget;
import org.junit.jupiter.api.Test;

class TeamsMutationCapabilityTests {
    @Test void actualActivityIdsAllowCompleteInlineImageDeletionButNeverPartialFileDeletion() {
        var provider=new TeamsMessageDeliveryProvider(null,null,null,null,"bot","tenant",null,null);
        var inline=target("actual-id","[{\"contentType\":\"image/png\",\"contentUrl\":\"data:image/png;base64,c3ludGhldGlj\"}]");
        assertThat(provider.mutationCapability(inline,true,false).canDelete()).isTrue();
        assertThat(provider.mutationCapability(inline,true,false).canEdit()).isFalse();
        assertThat(provider.mutationCapability(inline,true,true).canEdit()).isTrue();
        for(String payload:java.util.List.of("[]","invalid","[{\"contentType\":\"application/vnd.microsoft.card.adaptive\"}]",
                "[{\"contentType\":\"reference\",\"contentUrl\":\"https://files.example.test/document\"}]")) {
            var caps=provider.mutationCapability(target("actual-id",payload),true,true);
            assertThat(caps.canEdit()).isTrue();assertThat(caps.canDelete()).isFalse();
        }
        var missing=provider.mutationCapability(target(null,"[]"),false,true);
        assertThat(missing.canEdit()).isFalse();assertThat(missing.canDelete()).isFalse();
        assertThat(missing.editUnavailable()).isEqualTo("PROVIDER_TARGET_UNAVAILABLE");
    }
    private static MutationTarget target(String id,String payload) {
        return new MutationTarget(id,"conversation","28:bot","https://smba.trafficmanager.net/emea/","standardChannel",payload,"test-only");
    }
}

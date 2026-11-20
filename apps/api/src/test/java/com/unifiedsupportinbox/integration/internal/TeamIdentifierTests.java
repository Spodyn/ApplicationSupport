package com.unifiedsupportinbox.integration.internal;

import static org.assertj.core.api.Assertions.*;
import com.unifiedsupportinbox.ApiProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamIdentifierTests {
    String team="11111111-1111-4111-8111-111111111111",tenant="22222222-2222-4222-8222-222222222222";
    @Test void extractsGraphGroupGuidRatherThanBotThreadIdAndChecksTheTenant() {
        assertThat(TeamIdentifier.parse(team,tenant)).isEqualTo(UUID.fromString(team));
        assertThat(TeamIdentifier.parse("https://teams.cloud.microsoft/l/team/19%3Aopaque%40thread.tacv2/conversations?groupId="+team,tenant)).isEqualTo(UUID.fromString(team));
        assertThat(TeamIdentifier.parse("https://teams.microsoft.com/l/team/19%3Aopaque%40thread.tacv2/conversations?groupId="+team+"&tenantId="+tenant,tenant)).isEqualTo(UUID.fromString(team));
        assertThatThrownBy(()->TeamIdentifier.parse("https://teams.microsoft.com/l/team/thread/conversations?groupId="+team+"&tenantId="+team,tenant)).isInstanceOf(ApiProblemException.class);
    }
    @Test void rejectsForeignLinksAmbiguousParametersAndChannelOrBotIdentifiers() {
        for(String value:new String[]{"19:opaque@thread.tacv2","https://teams.microsoft.com.evil/l/team/a/conversations?groupId="+team,
                "https://teams.microsoft.com@evil.test/l/team/a/conversations?groupId="+team,
                "http://teams.microsoft.com/l/team/a/conversations?groupId="+team,
                "https://teams.microsoft.com/l/channel/a/conversations?groupId="+team,
                "https://teams.microsoft.com/l/team/a/conversations?groupId="+team+"&groupId="+team})
            assertThatThrownBy(()->TeamIdentifier.parse(value,tenant)).isInstanceOf(ApiProblemException.class);
    }
}

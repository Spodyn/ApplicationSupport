package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.ProviderConnectionConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TeamsTeamMembershipTests {
    final ProviderConnectionConfiguration connections=mock(ProviderConnectionConfiguration.class);
    final TeamsDirectoryClient directory=mock(TeamsDirectoryClient.class);
    final UUID integration=UUID.randomUUID(),team=UUID.randomUUID();
    final TeamsTeamMembership membership=new TeamsTeamMembership(connections,directory);
    final ObjectMapper json=new ObjectMapper();
    @Test void canonicalGroupIdAndOpaqueBotTeamIdResolveToTheSameSelectedTeam() {
        when(connections.teamScope(integration)).thenReturn(Optional.of(new ProviderConnectionConfiguration.TeamScope(integration,team,null,null)));
        var activity=json.readTree("{\"channelData\":{\"team\":{\"aadGroupId\":\""+team+"\",\"id\":\"19:general@thread.tacv2\"}}}");
        assertThat(membership.authorize(integration,activity)).isEqualTo(team);verifyNoInteractions(directory);
        when(directory.resolveBotTeam(integration,"https://smba.trafficmanager.net/emea/","19:general@thread.tacv2")).thenReturn(team);
        var opaque=json.readTree("{\"serviceUrl\":\"https://smba.trafficmanager.net/emea/\",\"channelData\":{\"team\":{\"id\":\"19:general@thread.tacv2\"}}}");
        assertThat(membership.authorize(integration,opaque)).isEqualTo(team);
        verify(connections,times(2)).verifyBotTeam(integration,team,"19:general@thread.tacv2");
    }
    @Test void foreignTeamMissingScopeAndGroupChatsAreRejected() {
        when(connections.teamScope(integration)).thenReturn(Optional.of(new ProviderConnectionConfiguration.TeamScope(integration,team,null,null)));
        for(String activity:List.of("{}","{\"channelData\":{\"team\":{\"aadGroupId\":\""+UUID.randomUUID()+"\"}}}","{\"conversation\":{\"conversationType\":\"groupChat\"},\"channelData\":{\"team\":{\"aadGroupId\":\""+team+"\"}}}"))
            assertThatThrownBy(()->membership.authorize(integration,json.readTree(activity))).isInstanceOf(ApiProblemException.class);
        when(connections.teamScope(integration)).thenReturn(Optional.empty());
        assertThatThrownBy(()->membership.authorize(integration,json.readTree("{}"))).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(directory);verify(connections,never()).verifyBotTeam(any(),any(),any());
    }
}

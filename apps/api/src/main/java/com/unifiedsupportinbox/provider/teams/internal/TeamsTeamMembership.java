package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Team authorization happens after JWT validation and before raw activity persistence. */
@Component
class TeamsTeamMembership {
    private final ProviderConnectionConfiguration connections;
    private final TeamsDirectoryClient directory;
    TeamsTeamMembership(ProviderConnectionConfiguration connections,TeamsDirectoryClient directory){this.connections=connections;this.directory=directory;}
    UUID authorize(UUID integration,JsonNode activity) {
        if("groupChat".equalsIgnoreCase(activity.path("conversation").path("conversationType").asText()))throw ApiProblemException.authenticationRequired();
        var scope=connections.teamScope(integration).orElseThrow(ApiProblemException::authenticationRequired);
        JsonNode data=activity.path("channelData").path("team");
        String aad=data.path("aadGroupId").asText("");String bot=data.path("id").asText("");
        // A group chat is not a channel of the selected team; its tenant alone is insufficient.
        if(aad.isBlank()&&bot.isBlank())throw ApiProblemException.authenticationRequired();
        if(!aad.isBlank()) {
            UUID claimed;
            try{claimed=UUID.fromString(aad);}catch(IllegalArgumentException e){throw ApiProblemException.authenticationRequired();}
            if(!scope.teamId().equals(claimed))throw ApiProblemException.authenticationRequired();
            if(!bot.isBlank())connections.verifyBotTeam(integration,claimed,bot);
            return claimed;
        }
        if(bot.equals(scope.botTeamId()))return scope.teamId();
        UUID resolved=directory.resolveBotTeam(integration,activity.path("serviceUrl").asText(),bot);
        if(!scope.teamId().equals(resolved))throw ApiProblemException.authenticationRequired();
        connections.verifyBotTeam(integration,resolved,bot);return resolved;
    }
}

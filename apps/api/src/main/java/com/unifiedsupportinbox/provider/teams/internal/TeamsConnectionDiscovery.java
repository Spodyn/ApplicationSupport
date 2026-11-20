package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.channel.*;
import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class TeamsConnectionDiscovery implements ProviderChannelDiscovery,ProviderConnectionRuntime,IntegrationConnectionTester {
    private final TeamsDirectoryClient directory;
    private final ProviderConnectionConfiguration connections;
    private final ChannelDiscoveryCatalog channels;
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final ObjectMapper json;
    private final String client,tenant,callback,defaultRef;
    TeamsConnectionDiscovery(TeamsDirectoryClient directory,ProviderConnectionConfiguration connections,ChannelDiscoveryCatalog channels,
            ProviderIntegrationCredentialLookup integrations,ConfiguredProviderSecretResolver secrets,ObjectMapper json,
            @Value("${usi.providers.teams.client-id:}")String client,@Value("${usi.providers.teams.tenant-id:}")String tenant,
            @Value("${usi.provider-callbacks.teams:}")String callback,@Value("${usi.providers.teams.default-secret-ref:teams/default}")String defaultRef) {
        this.directory=directory;this.connections=connections;this.channels=channels;this.integrations=integrations;this.secrets=secrets;
        this.json=json;this.client=client;this.tenant=tenant;this.callback=callback;this.defaultRef=defaultRef;
    }
    public IntegrationProvider provider(){return IntegrationProvider.TEAMS;}
    public Runtime runtime(UUID integration) {
        String ref=integrations.findForProvider(provider()).stream().filter(i->i.integrationId().equals(integration)).findFirst()
                .map(ProviderIntegrationCredentialLookup.CredentialReference::secretRef).orElse(defaultRef);
        var secret=secrets.resolve(ref,"teams-client-secret");boolean configured=guid(client)&&guid(tenant)&&secret.isPresent();
        secret.ifPresent(s->Arrays.fill(s,(byte)0));return new Runtime(callback,client,tenant,ref,configured);
    }
    public int discover(ProviderConnectionConfiguration.Claim claim) {
        if(!runtime(claim.integrationId()).configured())throw new Failure("TEAMS_BOT_CONFIGURATION_MISSING",Duration.ofMinutes(1));
        var scope=connections.teamScope(claim.integrationId()).orElseThrow(()->new Failure("TEAMS_TEAM_SELECTION_REQUIRED",null));
        if(scope.defaultCustomerId()==null)throw new Failure("TEAMS_DEFAULT_CUSTOMER_REQUIRED",null);
        connections.adoptConfiguredTeamsTenant(claim.integrationId(),tenant);
        List<DiscoveredChannel> snapshot=new ArrayList<>();
        try {
            for(var c:directory.channels(claim.integrationId(),scope.teamId())) {
                if(!"standard".equals(c.path("membershipType").asText()))continue;
                String id=c.path("id").asText("");String name=c.path("displayName").asText("");
                if(id.isBlank()||name.isBlank())throw new Failure("TEAMS_DISCOVERY_INVALID_CHANNEL",null);
                snapshot.add(new DiscoveredChannel(claim.integrationId(),id,name,ChannelGroupingStrategy.TEAMS_ROOT_REPLIES,true,null,
                        json.writeValueAsString(Map.of("teamsContextType","standardChannel","teamsTeamId",scope.teamId().toString())),scope.defaultCustomerId()));
            }
        } catch(ProviderAttachmentException failure){throw new Failure(failure.errorCode(),failure.retryable()?Duration.ofSeconds(30):null);}
        return channels.replaceClaim(claim,scope.teamId(),snapshot).size();
    }
    public boolean supports(IntegrationProvider provider){return provider==provider();}
    public Result test(Request request) {
        if(!runtime(request.integrationId()).configured())return new Result(Outcome.UNAVAILABLE,"TEAMS_BOT_CONFIGURATION_MISSING");
        var scope=connections.teamScope(request.integrationId());if(scope.isEmpty())return new Result(Outcome.UNAVAILABLE,"TEAMS_TEAM_SELECTION_REQUIRED");
        connections.adoptConfiguredTeamsTenant(request.integrationId(),tenant);
        try {directory.channels(request.integrationId(),scope.get().teamId());connections.requestDiscovery(request.integrationId());return Result.success();}
        catch(Failure failure){return new Result(failure.code.contains("403")?Outcome.UNAUTHORIZED:Outcome.UNAVAILABLE,failure.code);}
        catch(ProviderAttachmentException failure){return new Result(Outcome.UNAUTHORIZED,failure.errorCode());}
    }
    private static boolean guid(String value){try{return value!=null&&UUID.fromString(value).toString().equalsIgnoreCase(value);}catch(IllegalArgumentException e){return false;}}
}

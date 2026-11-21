package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.audit.*;
import com.unifiedsupportinbox.integration.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/integrations")
class ConnectionOnboardingController {
    private final IntegrationService integrations;
    private final IntegrationRepository repository;
    private final ProviderConnectionConfiguration configuration;
    private final List<ProviderConnectionRuntime> runtime;
    private final AuditEventStore audit;
    private final TeamsInstallationPackage packages;
    ConnectionOnboardingController(IntegrationService integrations,IntegrationRepository repository,ProviderConnectionConfiguration configuration,
            List<ProviderConnectionRuntime> runtime,AuditEventStore audit,TeamsInstallationPackage packages) {
        this.integrations=integrations;this.repository=repository;this.configuration=configuration;this.runtime=runtime;this.audit=audit;this.packages=packages;
    }
    record RuntimeView(IntegrationProvider provider,String callbackUrl,boolean botConfigured,String clientId) { }
    record View(UUID integrationId,boolean automaticDiscovery,String state,String callbackUrl,boolean botConfigured,
            UUID teamId,UUID defaultCustomerId,Instant lastDiscoveredAt,Instant nextDiscoveryAt,String errorCode,int discoveredChannels) { }
    record TeamsInput(String displayName,String team,UUID defaultCustomerId,Boolean automaticDiscovery) { }
    record SlackInput(String displayName,String workspaceExternalId,String workspaceName,String secretRef,Boolean automaticDiscovery) { }
    record DiscoveryInput(boolean automaticDiscovery) { }

    @GetMapping("/setup") List<RuntimeView> runtime(Authentication actor) {
        authorize(actor);return runtime.stream().map(r->view(r.provider(),r.runtime(null))).toList();
    }
    @GetMapping("/{integrationId}/onboarding") View get(@PathVariable UUID integrationId,Authentication actor) {
        authorize(actor);return projection(integrationId);
    }
    @PutMapping("/{integrationId}/onboarding") @Transactional
    View configure(@PathVariable UUID integrationId,@RequestBody DiscoveryInput input,Authentication actor) {
        authorize(actor);configuration.configure(integrationId,input.automaticDiscovery());
        audit.append(AuditActors.type(actor),AuditActors.userId(actor),"CHANNEL_DISCOVERY_CONFIGURED","integration",integrationId,null,Map.of("automatic",input.automaticDiscovery()));
        return projection(integrationId);
    }
    @PostMapping("/{integrationId}/onboarding/discover") @Transactional
    View discover(@PathVariable UUID integrationId,Authentication actor) {
        authorize(actor);projection(integrationId);configuration.requestDiscovery(integrationId);
        audit.append(AuditActors.type(actor),AuditActors.userId(actor),"CHANNEL_DISCOVERY_REQUESTED","integration",integrationId,null,Map.of());
        return projection(integrationId);
    }
    @PostMapping("/slack") @Transactional @ResponseStatus(HttpStatus.CREATED)
    IntegrationView createSlack(@RequestBody SlackInput input,Authentication actor) {return saveSlack(null,input,actor);}
    @PutMapping("/{integrationId}/slack") @Transactional
    IntegrationView updateSlack(@PathVariable UUID integrationId,@RequestBody SlackInput input,Authentication actor) {return saveSlack(integrationId,input,actor);}
    private IntegrationView saveSlack(UUID id,SlackInput input,Authentication actor) {
        authorize(actor);var current=id==null?null:existing(id,IntegrationProvider.SLACK);
        var r=runtime(IntegrationProvider.SLACK,id);
        String ref=input.secretRef()==null||input.secretRef().isBlank()?current==null||current.secretRef()==null?r.secretRef():current.secretRef():input.secretRef();
        var fields=new IntegrationService.IntegrationConfigurationInput(IntegrationProvider.SLACK,input.displayName(),input.workspaceExternalId(),
                input.workspaceName()==null||input.workspaceName().isBlank()?input.displayName():input.workspaceName(),ref);
        var saved=id==null?integrations.create(actor,fields):integrations.update(actor,id,fields);
        if(input.automaticDiscovery()!=null)configuration.configure(saved.id(),input.automaticDiscovery());
        return saved;
    }
    @PostMapping("/teams") @Transactional @ResponseStatus(HttpStatus.CREATED)
    IntegrationView createTeams(@RequestBody TeamsInput input,Authentication actor) {return saveTeams(null,input,actor);}
    @PutMapping("/{integrationId}/teams") @Transactional
    IntegrationView updateTeams(@PathVariable UUID integrationId,@RequestBody TeamsInput input,Authentication actor) {return saveTeams(integrationId,input,actor);}
    private IntegrationView saveTeams(UUID id,TeamsInput input,Authentication actor) {
        authorize(actor);var r=runtime(IntegrationProvider.TEAMS,id);
        UUID team=TeamIdentifier.parse(input.team(),r.tenantId());
        var current=id==null?null:existing(id,IntegrationProvider.TEAMS);
        var previous=id==null?Optional.<ProviderConnectionConfiguration.TeamScope>empty():configuration.teamScope(id);
        String tenant=r.tenantId().isBlank()?current==null?"teams-unconfigured":current.workspaceExternalId():r.tenantId();
        var fields=new IntegrationService.IntegrationConfigurationInput(IntegrationProvider.TEAMS,input.displayName(),tenant,input.displayName(),current==null||current.secretRef()==null?r.secretRef():current.secretRef());
        IntegrationView saved;
        if(current==null) {
            saved=integrations.create(actor,fields);configuration.selectTeam(saved.id(),team,input.defaultCustomerId());
        } else {
            // Lock settings/team before updating Integration, matching discovery and ingress lock order.
            configuration.selectTeam(id,team,input.defaultCustomerId());saved=integrations.update(actor,id,fields);
            if(current.status()==IntegrationStatus.ENABLED&&previous.map(s->s.teamId().equals(team)).orElse(false)&&tenant.equals(current.workspaceExternalId())) {
                saved=integrations.setStatus(actor,id,IntegrationStatus.ENABLED);
                repository.updateHealthPreservingLastEvent(id,current.health(),current.lastErrorCode());saved=repository.findById(id).orElseThrow().toView();
            }
        }
        if(input.automaticDiscovery()!=null)configuration.configure(saved.id(),input.automaticDiscovery());
        audit.append(AuditActors.type(actor),AuditActors.userId(actor),"TEAMS_TEAM_SELECTED","integration",saved.id(),null,Map.of("teamId",team.toString(),"defaultCustomerId",input.defaultCustomerId().toString()));
        return saved;
    }
    @GetMapping(value="/teams/package",produces="application/octet-stream")
    ResponseEntity<org.springframework.core.io.Resource> installationPackage(Authentication actor) {
        authorize(actor);var r=runtime(IntegrationProvider.TEAMS,null);
        if(!r.configured())throw ApiProblemException.validationFailed("Configure the Teams bot on this server before downloading its installation package.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=application-support-teams.zip")
                .cacheControl(CacheControl.noStore()).body(new org.springframework.core.io.ByteArrayResource(packages.create(r.clientId(),r.callbackUrl())));
    }
    private View projection(UUID id) {
        var integration=repository.findById(id).orElseThrow(()->ApiProblemException.notFound("Integration was not found."));
        var settings=configuration.settings(id);var r=runtime(integration.provider(),id);var team=configuration.teamScope(id);
        String state=!r.configured()?"MISSING_BOT_CONFIGURATION":integration.provider()==IntegrationProvider.TEAMS&&team.isEmpty()?"TEAM_REQUIRED":
                integration.provider()==IntegrationProvider.TEAMS&&team.get().defaultCustomerId()==null?"DEFAULT_CUSTOMER_REQUIRED":settings.discoveryState();
        return new View(id,settings.automaticDiscovery(),state,r.callbackUrl(),r.configured(),team.map(ProviderConnectionConfiguration.TeamScope::teamId).orElse(null),
                team.map(ProviderConnectionConfiguration.TeamScope::defaultCustomerId).orElse(null),settings.lastDiscoveredAt(),settings.nextDiscoveryAt(),settings.errorCode(),settings.discoveredChannels());
    }
    private ProviderConnectionRuntime.Runtime runtime(IntegrationProvider provider,UUID id) {
        return runtime.stream().filter(r->r.provider()==provider).findFirst().orElseThrow(()->ApiProblemException.validationFailed("Provider setup is not supported.")).runtime(id);
    }
    private static RuntimeView view(IntegrationProvider provider,ProviderConnectionRuntime.Runtime r){return new RuntimeView(provider,r.callbackUrl(),r.configured(),r.clientId());}
    private IntegrationRecord existing(UUID id,IntegrationProvider provider) {
        var current=repository.findById(id).orElseThrow(()->ApiProblemException.notFound("Integration was not found."));
        if(current.provider()!=provider)throw ApiProblemException.validationFailed("Integration provider cannot be changed.");return current;
    }
    private static void authorize(Authentication actor) {
        if(actor==null||!actor.isAuthenticated()||actor.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN"))
                ||actor.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("manage_integrations")))throw ApiProblemException.accessDenied();
    }
}

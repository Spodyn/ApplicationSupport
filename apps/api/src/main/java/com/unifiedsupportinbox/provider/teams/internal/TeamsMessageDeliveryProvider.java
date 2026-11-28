package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="usi.providers.teams.outbound.enabled", havingValue="true", matchIfMissing=true)
class TeamsMessageDeliveryProvider implements MessageDeliveryProvider {
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final TeamsRouteStore routes;
    private final TeamsConnectorClient connector;
    private final String clientId,tenantId;
    private final TeamsOutboundAttachments files;
    private final TeamsOutboundAcceptance acceptance;
    TeamsMessageDeliveryProvider(ProviderIntegrationCredentialLookup integrations,ConfiguredProviderSecretResolver secrets,
            TeamsRouteStore routes,TeamsConnectorClient connector,@Value("${usi.providers.teams.client-id:}") String clientId,
            @Value("${usi.providers.teams.tenant-id:}") String tenantId,TeamsOutboundAttachments files,TeamsOutboundAcceptance acceptance) {
        this.integrations=integrations;this.secrets=secrets;this.routes=routes;this.connector=connector;
        this.clientId=clientId;this.tenantId=tenantId;this.files=files;this.acceptance=acceptance;
    }
    @Override public boolean supports(IntegrationProvider provider) { return provider==IntegrationProvider.TEAMS; }
    @Override public MutationCapability mutationCapability(MutationTarget target,boolean hasAttachments,boolean hasText) {
        if(target==null||target.providerMessageRef()==null||target.providerMessageRef().isBlank()||target.senderId()==null
                ||target.conversationId()==null||!TeamsJwtAuthenticator.safeServiceUrl(target.serviceUrl()))
            return new MutationCapability(false,false,"PROVIDER_TARGET_UNAVAILABLE","PROVIDER_TARGET_UNAVAILABLE");
        boolean editable=hasText || (hasAttachments && hasSavedAttachments(target));
        boolean completeDelete=!hasAttachments || onlyInlineAttachments(target);
        return new MutationCapability(editable,completeDelete,editable?null:"NO_TEXT_RESOURCE",completeDelete?null:"MULTIPLE_PROVIDER_RESOURCES");
    }
    private static boolean hasSavedAttachments(MutationTarget target) {
        try {
            var attachments=JSON.readTree(target.attachmentPayload());
            return attachments!=null&&attachments.isArray()&&!attachments.isEmpty();
        }catch(RuntimeException invalid){return false;}
    }
    private static boolean onlyInlineAttachments(MutationTarget target) {
        try {
            var attachments=JSON.readTree(target.attachmentPayload());
            if(attachments==null||!attachments.isArray()||attachments.isEmpty())return false;
            for(var attachment:attachments) {
                String type=attachment.path("contentType").asText();
                if(!java.util.Set.of("image/png","image/jpeg","image/gif").contains(type)
                        ||!attachment.path("contentUrl").asText().startsWith("data:"+type+";base64,"))return false;
            }
            return true;
        }catch(RuntimeException invalid){return false;}
    }
    @Override public MutationResult mutate(MutationCommand command) {
        var target=command.target();
        var matches=integrations.findEnabledForProvider(IntegrationProvider.TEAMS).stream().filter(i->i.integrationId().equals(command.integrationId())
                &&tenantId.equals(i.workspaceExternalId())&&java.util.Objects.equals(i.secretRef(),target.credentialRef())).toList();
        if(matches.size()!=1||clientId.isBlank())return MutationResult.failed("TEAMS_CREDENTIAL_REFERENCE_CHANGED");
        if(!java.util.List.of(clientId,"28:"+clientId).contains(target.senderId())
                ||!routes.approvedContext(command.integrationId(),command.externalChannelId(),target.contextType()))
            return MutationResult.failed("TEAMS_CONVERSATION_ROUTE_MISSING");
        byte[] secret=secrets.resolve(matches.getFirst().secretRef(),"teams-client-secret").orElse(null);
        if(secret==null)return MutationResult.failed("TEAMS_CLIENT_SECRET_MISSING");
        try{return connector.mutate(connector.token(clientId,tenantId,secret),command);}
        catch(TeamsConnectorClient.TokenUnavailable failure){return failure.retryable?MutationResult.retry("TEAMS_TOKEN_UNAVAILABLE",null):MutationResult.failed("TEAMS_AUTH_FAILED");}
        finally{Arrays.fill(secret,(byte)0);}
    }
    @Override public DeliveryResult deliver(DeliveryCommand command) {
        if (command==null || command.provider()!=IntegrationProvider.TEAMS) return DeliveryResult.permanentFailure("TEAMS_INVALID_COMMAND");
        if (command.acceptedBodyRef()!=null) return DeliveryResult.sent(command.acceptedBodyRef());
        if (acceptance.accepted(command))return DeliveryResult.sent(null);
        var matches=integrations.findEnabledForProvider(IntegrationProvider.TEAMS).stream()
                .filter(i->i.integrationId().equals(command.integrationId()) && tenantId.equals(i.workspaceExternalId())).toList();
        if (matches.size()!=1 || clientId.isBlank()) return DeliveryResult.permanentFailure("TEAMS_CREDENTIAL_REFERENCE_MISSING");
        var route=routes.find(command.integrationId(),command.externalConversationId(),command.externalThreadKey());
        if (route==null || !java.util.List.of(clientId,"28:"+clientId).contains(route.botId())
                || !routes.approvedContext(command.integrationId(),command.externalConversationId(),route.contextType()))
            return DeliveryResult.permanentFailure("TEAMS_CONVERSATION_ROUTE_MISSING");
        byte[] secret=secrets.resolve(matches.getFirst().secretRef(),"teams-client-secret").orElse(null);
        if (secret==null) return DeliveryResult.permanentFailure("TEAMS_CLIENT_SECRET_MISSING");
        try {
            var prepared=files.prepare(command);
            String token=connector.token(clientId,tenantId,secret);
            return prepared.isEmpty()?connector.send(token,route,command):connector.send(token,route,command,prepared);
        }
        catch(com.unifiedsupportinbox.provider.ProviderAttachmentException failed){return failed.retryable()
                ? DeliveryResult.transientFailure(failed.errorCode(),failed.retryAfter()):DeliveryResult.permanentFailure(failed.errorCode());}
        catch(com.unifiedsupportinbox.storage.AttachmentQuarantinedException failed){return DeliveryResult.permanentFailure("ATTACHMENT_NOT_CLEAN");}
        catch (TeamsConnectorClient.TokenUnavailable failed) { return failed.retryable
                ? DeliveryResult.transientFailure("TEAMS_TOKEN_UNAVAILABLE",null) : DeliveryResult.permanentFailure("TEAMS_AUTH_FAILED"); }
        finally { Arrays.fill(secret,(byte)0); }
    }
}

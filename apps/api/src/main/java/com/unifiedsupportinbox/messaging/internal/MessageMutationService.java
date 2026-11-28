package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.audit.*;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.*;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Mutation state never enters delivery finalization, workflow or SLA services. */
@Service
class MessageMutationService implements OutboundMutationCallbacks {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "RETRY", "UNCERTAIN");
    private static final String SELECT = """
            SELECT m.id, m.case_id, m.author_user_id, m.kind, m.inbound, m.body, m.body_format,
                   m.delivery_status, m.external_message_id, m.mutation_revision, m.deleted_at,
                   m.provider_mutated_at, c.provider, c.integration_id, c.external_conversation_id,
                   i.status AS integration_status, i.secret_ref,
                   EXISTS(SELECT 1 FROM attachments a WHERE a.message_id=m.id) AS has_attachments,
                   EXISTS(SELECT 1 FROM attachments a WHERE a.message_id=m.id AND a.scan_status<>'CLEAN') AS unsafe_attachments,
                   t.provider_message_ref, t.conversation_id, t.sender_id, t.service_url, t.context_type,
                   t.attachment_payload::text AS attachment_payload, t.credential_ref
            FROM messages m JOIN cases c ON c.id=m.case_id JOIN integrations i ON i.id=c.integration_id
            LEFT JOIN message_mutation_targets t ON t.message_id=m.id
            """;
    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor commands;
    private final ObjectMapper json;
    private final AuditEventStore audit;
    private final MessageCreatedOutboxPublisher events;
    private final OutboxEventStore outbox;
    private final ObjectProvider<MessageDeliveryProvider> providers;
    private final MessageDeliveryWorkerProperties properties;
    private final Clock clock;

    @Autowired
    MessageMutationService(JdbcTemplate jdbc, IdempotentCommandExecutor commands, ObjectMapper json,
            AuditEventStore audit, MessageCreatedOutboxPublisher events, OutboxEventStore outbox,
            ObjectProvider<MessageDeliveryProvider> providers, MessageDeliveryWorkerProperties properties, ObjectProvider<Clock> clocks) {
        this(jdbc, commands, json, audit, events, outbox, providers, properties, clocks.getIfAvailable(Clock::systemUTC));
    }

    MessageMutationService(JdbcTemplate jdbc, IdempotentCommandExecutor commands, ObjectMapper json,
            AuditEventStore audit, MessageCreatedOutboxPublisher events, OutboxEventStore outbox,
            ObjectProvider<MessageDeliveryProvider> providers, MessageDeliveryWorkerProperties properties, Clock clock) {
        this.jdbc=jdbc; this.commands=commands; this.json=json; this.audit=audit; this.events=events;
        this.outbox=outbox; this.providers=providers; this.properties=properties; this.clock=clock;
    }

    IdempotencyResult request(UUID messageId, UUID actor, String key, String action, String body, long revision, String correlation) {
        if (!Set.of("EDIT", "DELETE").contains(Objects.toString(action, "")) || revision < 0
                || (action.equals("EDIT") && (body==null || body.isBlank() || body.length()>40000))
                || (action.equals("DELETE") && body!=null)) {
            throw ApiProblemException.validationFailed("A valid mutation action, revision and message text are required.");
        }
        requireAuthor(read(messageId, false), actor);
        Map<String,Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("messageId", messageId); fingerprint.put("action", action);
        fingerprint.put("body", body); fingerprint.put("revision", revision);
        return commands.execute(actor, "message.mutate:"+messageId, key, fingerprint, () -> {
            Message row=read(messageId, true);
            requireAuthor(row, actor);
            if (row.revision()!=revision) throw ApiProblemException.conflict("Message content changed. Reload before mutating it.");
            requireCapability(row, action);
            if (active(messageId)!=null) throw ApiProblemException.conflict("A mutation is already pending for this Message.");
            UUID operation=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO message_mutations(id,message_id,actor_user_id,action,expected_revision,
                        requested_body,created_at,retry_until,max_attempts,correlation_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, operation,messageId,actor,action,revision,body,at(now()),at(now().plus(properties.retryWindow())),properties.maxAutomaticAttempts(),correlation);
            audit(row,actor,"MESSAGE_MUTATION_REQUESTED",operation,action);
            wakeup(operation,row,correlation);
            return new IdempotencyResponse(202,json.valueToTree(Map.of("operationId",operation,"state","QUEUED")));
        });
    }

    IdempotencyResult retry(UUID messageId, UUID operation, UUID actor, String key) {
        requireAuthor(read(messageId,false),actor);
        return commands.execute(actor,"message.mutation.retry:"+operation,key,Map.of("operationId",operation),()->{
            Message row=read(messageId,true); requireAuthor(row,actor);
            Operation op=operation(operation,true);
            if(op==null)throw ApiProblemException.notFound("Mutation was not found.");
            if (!op.messageId().equals(messageId) || !op.actor().equals(actor)) throw ApiProblemException.accessDenied();
            requireCapability(row,op.action());
            if (!op.state().equals("FAILED") || !op.retryable() || row.revision()!=op.revision()
                    || active(messageId)!=null || !op.id().equals(latest(messageId).id())) {
                throw ApiProblemException.conflict("This mutation cannot be safely retried.");
            }
            jdbc.update("""
                    UPDATE message_mutations SET state='QUEUED',finished_at=NULL,error_code=NULL,retryable=false,
                        retry_until=?,max_attempts=attempt_count+?,next_attempt_at=NULL WHERE id=?
                    """,at(now().plus(properties.retryWindow())),properties.maxAutomaticAttempts(),operation);
            audit(row,actor,"MESSAGE_MUTATION_RETRIED",operation,op.action());
            wakeup(operation,row,op.correlation());
            return new IdempotencyResponse(202,json.valueToTree(Map.of("operationId",operation,"state","QUEUED")));
        });
    }

    @Transactional
    void recordTarget(UUID messageId, MutationTarget target) {
        Message row=read(messageId,true);
        if (!"SENDING".equals(row.delivery()) || target==null || target.providerMessageRef()==null
                || !target.providerMessageRef().equals(row.externalRef())) throw new IllegalStateException("Mutation target does not match accepted delivery.");
        jdbc.update("""
                INSERT INTO message_mutation_targets(message_id,provider_message_ref,conversation_id,sender_id,
                    service_url,context_type,attachment_payload,credential_ref) VALUES (?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT(message_id) DO NOTHING
                """, messageId,target.providerMessageRef(),target.conversationId(),target.senderId(),target.serviceUrl(),
                target.contextType(),Objects.toString(target.attachmentPayload(),"[]"),row.secretRef());
    }

    @Transactional
    Claim claim(UUID operation) {
        Operation hint=operation(operation,false);
        if (hint==null) return null;
        Message row=read(hint.messageId(),true);
        Operation op=operation(operation,true);
        if (!Set.of("QUEUED","RETRY").contains(op.state()) || op.nextAttempt()!=null) return null;
        try {
            requireAuthor(row,op.actor()); requireCapability(row,op.action());
            if (row.revision()!=op.revision()) throw ApiProblemException.conflict("Message content changed.");
        } catch (ApiProblemException denied) {
            fail(op,row,"AUTHORIZATION_OR_CAPABILITY_CHANGED",false); return null;
        }
        UUID lease=UUID.randomUUID();
        jdbc.update("UPDATE message_mutations SET state='RUNNING',attempt_count=attempt_count+1,dispatched_at=?,lease_token=?,lease_until=? WHERE id=?",
                at(now()),lease,at(now().plus(properties.claimLease())),operation);
        changed(row,op.correlation());
        return new Claim(op.id(),row,op.actor(),op.action(),op.body(),op.revision(),lease,op.attempts()+1,op.maxAttempts(),op.retryUntil(),op.correlation());
    }

    /** Called after provider I/O, using a fenced claim. */
    @Transactional
    void finish(Claim claim, MutationResult result) {
        Message row=read(claim.message().id(),true);
        Operation op=operation(claim.operation(),true);
        if (op==null || !Set.of("RUNNING","UNCERTAIN").contains(op.state()) || !claim.lease().equals(op.lease())) return;
        if (row.deletedAt()!=null || row.revision()!=claim.revision()) {
            uncertain(op,row,"MESSAGE_CHANGED_DURING_MUTATION"); return;
        }
        if (result==null) result=MutationResult.uncertain("PROVIDER_INVALID_RESULT");
        switch(result.outcome()) {
            case CONFIRMED -> confirm(op,row);
            case UNCERTAIN -> uncertain(op,row,safe(result.errorCode()));
            case FAILED -> fail(op,row,safe(result.errorCode()),false);
            case RETRY -> {
                Duration exponential=properties.baseRetryDelay().multipliedBy(1L<<Math.min(20,claim.attempt()-1));
                if(exponential.compareTo(properties.maxRetryDelay())>0)exponential=properties.maxRetryDelay();
                Duration delay=exponential.multipliedBy(java.util.concurrent.ThreadLocalRandom.current().nextLong(80,121)).dividedBy(100);
                if(result.retryAfter()!=null && result.retryAfter().compareTo(delay)>0)delay=result.retryAfter();
                Instant next=now().plus(delay);
                if(claim.attempt()>=claim.maxAttempts() || !next.isBefore(claim.retryUntil())) fail(op,row,safe(result.errorCode()),true);
                else {
                    jdbc.update("UPDATE message_mutations SET state='RETRY',next_attempt_at=?,error_code=?,retryable=true,lease_token=NULL,lease_until=NULL WHERE id=?",
                            at(next),safe(result.errorCode()),op.id());
                    audit(row,op.actor(),"MESSAGE_MUTATION_RETRY_SCHEDULED",op.id(),op.action()); changed(row,op.correlation());
                }
            }
        }
    }

    @Transactional
    int redispatch() {
        var expired=jdbc.query("SELECT id FROM message_mutations WHERE state='RUNNING' AND lease_until<=? ORDER BY lease_until LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs,n)->rs.getObject(1,UUID.class),at(now()),properties.batchSize());
        // Never replay a request whose provider-side outcome is unknown after a worker restart.
        for(UUID id:expired) {
            jdbc.update("UPDATE message_mutations SET state='UNCERTAIN',error_code='MUTATION_OUTCOME_UNKNOWN',lease_until=NULL WHERE id=?",id);
            Operation op=operation(id,false); Message row=read(op.messageId(),false);
            audit(row,op.actor(),"MESSAGE_MUTATION_UNCERTAIN",id,op.action()); changed(row,op.correlation());
        }
        var due=jdbc.query("SELECT id FROM message_mutations WHERE state IN ('QUEUED','RETRY') AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs,n)->rs.getObject(1,UUID.class),at(now()),properties.batchSize());
        for(UUID id:due) {
            Operation op=operation(id,false); jdbc.update("UPDATE message_mutations SET next_attempt_at=NULL WHERE id=?",id);
            wakeup(id,read(op.messageId(),false),op.correlation());
        }
        return due.size()+expired.size();
    }

    Map<UUID,View> views(List<UUID> ids, UUID viewer) {
        if(ids.isEmpty())return Map.of();
        String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
        Map<UUID,View> result=new HashMap<>();
        var rows=jdbc.query(SELECT+" WHERE m.id IN ("+slots+")",this::map,ids.toArray());
        Map<UUID,Operation> latest=new HashMap<>();
        jdbc.query("SELECT DISTINCT ON (message_id) * FROM message_mutations WHERE message_id IN ("+slots+") ORDER BY message_id,sequence DESC",this::op,ids.toArray())
                .forEach(op->latest.put(op.messageId(),op));
        boolean activeViewer=activeAccount(viewer);
        var caseIds=rows.stream().map(Message::caseId).distinct().toList();
        Set<UUID> ignoredCases=new HashSet<>();
        if(activeViewer && !caseIds.isEmpty()) {
            var parameters=new ArrayList<Object>();parameters.add(viewer);parameters.addAll(caseIds);
            ignoredCases.addAll(jdbc.query("SELECT case_id FROM case_ignore_votes WHERE user_id=? AND case_id IN ("+String.join(",",Collections.nCopies(caseIds.size(),"?"))+")",(rs,n)->rs.getObject(1,UUID.class),parameters.toArray()));
        }
        for(Message row:rows) {
            boolean author=activeViewer && viewer.equals(row.author()) && "SUPPORT".equals(row.kind()) && !row.inbound() && !ignoredCases.contains(row.caseId());
            MutationCapability caps=capability(row);
            Operation op=latest.get(row.id()); boolean pending=op!=null && ACTIVE.contains(op.state());
            String common=!author?"NOT_AUTHOR":row.deletedAt()!=null?"MESSAGE_DELETED":!successful(row)?"DELIVERY_NOT_CONFIRMED":pending?"MUTATION_PENDING":null;
            result.put(row.id(),new View(author,common==null&&caps.canEdit(),common==null&&caps.canDelete(),
                    common!=null?common:caps.editUnavailable(),common!=null?common:caps.deleteUnavailable(),row.revision(),
                    op==null?null:op.id(),op==null?null:op.action(),op==null?null:op.state(),op==null?null:op.error(),
                    author&&op!=null&&op.state().equals("FAILED")&&op.retryable()&&row.revision()==op.revision()&&capsAvailable(caps,op.action())));
        }
        return result;
    }

    @Override @Transactional
    public boolean observe(IntegrationProvider provider,UUID integration,String channel,String conversation,String reference,String sender,String body,boolean deleted,Instant occurred) {
        if(reference==null || occurred==null)return false;
        var matches=jdbc.query(SELECT+"""
                 WHERE c.provider=? AND c.integration_id=? AND c.external_conversation_id=?
                   AND COALESCE(t.conversation_id,c.external_conversation_id)=?
                   AND (t.provider_message_ref=? OR (t.message_id IS NULL AND c.provider='SLACK' AND m.external_message_id=?))
                   AND (? OR t.sender_id=? OR (t.message_id IS NULL AND c.provider='SLACK'))
                   AND m.kind='SUPPORT' AND NOT m.inbound FOR UPDATE OF m
                """,this::map,provider.name(),integration,channel,conversation,reference,reference,deleted,sender);
        for(Message row:matches) {
            if(row.deletedAt()!=null)return true;
            Operation op=active(row.id());
            if(deleted) {
                if(op!=null && op.action().equals("DELETE"))confirm(op,row);
                else {
                    retain(row); jdbc.update("UPDATE messages SET deleted_at=?,mutation_revision=mutation_revision+1,provider_mutated_at=? WHERE id=?",at(occurred),at(occurred),row.id());
                    if(op!=null)fail(op,row,"PROVIDER_MESSAGE_DELETED",false);
                    providerChanged(row,"SUPPORT_MESSAGE_DELETED",occurred);
                }
                return true;
            }
            if(body==null)return true;
            if(op!=null && op.action().equals("EDIT") && Set.of("RUNNING","UNCERTAIN").contains(op.state()) && op.dispatchedAt()!=null && !occurred.isBefore(op.dispatchedAt()) && body.equals(op.body()) && row.revision()==op.revision()) {
                confirm(op,row); jdbc.update("UPDATE messages SET provider_mutated_at=? WHERE id=?",at(occurred),row.id()); return true;
            }
            if(row.callbackAt()!=null && !occurred.isAfter(row.callbackAt()))return true;
            if(body.equals(row.body())) { jdbc.update("UPDATE messages SET provider_mutated_at=? WHERE id=?",at(occurred),row.id()); return true; }
            retain(row);
            jdbc.update("UPDATE messages SET body=?,edited_at=?,provider_mutated_at=?,mutation_revision=mutation_revision+1 WHERE id=?",body,at(occurred),at(occurred),row.id());
            if(op!=null)uncertain(op,row,"MESSAGE_CHANGED_DURING_MUTATION");
            providerChanged(row,"SUPPORT_MESSAGE_EDITED",occurred);
        }
        return !matches.isEmpty();
    }

    private void confirm(Operation op,Message row) {
        retain(row);
        if(op.action().equals("EDIT"))jdbc.update("UPDATE messages SET body=?,edited_at=?,provider_mutated_at=GREATEST(provider_mutated_at,?),mutation_revision=mutation_revision+1 WHERE id=? AND deleted_at IS NULL",op.body(),at(now()),at(now()),row.id());
        else jdbc.update("UPDATE messages SET deleted_at=?,provider_mutated_at=GREATEST(provider_mutated_at,?),mutation_revision=mutation_revision+1 WHERE id=? AND deleted_at IS NULL",at(now()),at(now()),row.id());
        jdbc.update("UPDATE message_mutations SET state='CONFIRMED',finished_at=?,error_code=NULL,retryable=false,lease_token=NULL,lease_until=NULL,next_attempt_at=NULL WHERE id=?",at(now()),op.id());
        audit(row,op.actor(),op.action().equals("EDIT")?"SUPPORT_MESSAGE_EDITED":"SUPPORT_MESSAGE_DELETED",op.id(),op.action());
        jdbc.update("UPDATE cases SET updated_at=? WHERE id=?",at(now()),row.caseId());
        changed(row,op.correlation());
    }
    private void retain(Message row) {
        jdbc.update("INSERT INTO message_content_versions(message_id,revision,body,body_format,edited_at,deleted_at) SELECT id,mutation_revision,body,body_format,edited_at,deleted_at FROM messages WHERE id=? ON CONFLICT DO NOTHING",row.id());
    }
    private void providerChanged(Message row,String action,Instant occurred) {
        audit.append(AuditActorType.PROVIDER,null,action,"message",row.id(),row.caseId(),Map.of("provider",row.provider().name()));
        jdbc.update("UPDATE cases SET updated_at=? WHERE id=?",at(now()),row.caseId()); changed(row,UUID.randomUUID().toString());
    }
    private void fail(Operation op,Message row,String code,boolean retryable) {
        jdbc.update("UPDATE message_mutations SET state='FAILED',finished_at=?,error_code=?,retryable=?,lease_token=NULL,lease_until=NULL,next_attempt_at=NULL WHERE id=?",at(now()),code,retryable,op.id());
        audit.append(AuditActorType.USER,op.actor(),"MESSAGE_MUTATION_FAILED","message",row.id(),row.caseId(),Map.of("operationId",op.id().toString(),"mutation",op.action(),"errorCode",code)); changed(row,op.correlation());
    }
    private void uncertain(Operation op,Message row,String code) {
        jdbc.update("UPDATE message_mutations SET state='UNCERTAIN',error_code=?,retryable=false,lease_token=NULL,lease_until=NULL,next_attempt_at=NULL WHERE id=?",code,op.id());
        audit.append(AuditActorType.USER,op.actor(),"MESSAGE_MUTATION_UNCERTAIN","message",row.id(),row.caseId(),Map.of("operationId",op.id().toString(),"mutation",op.action(),"errorCode",code)); changed(row,op.correlation());
    }
    private void requireAuthor(Message row,UUID actor) { if(!authorized(row,actor))throw ApiProblemException.accessDenied(); }
    private boolean activeAccount(UUID actor) {
        return actor!=null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND active AND role IN ('USER','ADMIN')
                    AND (valid_from IS NULL OR valid_from<=CURRENT_TIMESTAMP) AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP))
                """,Boolean.class,actor));
    }
    private boolean authorized(Message row,UUID actor) {
        if(actor==null || !actor.equals(row.author()) || !"SUPPORT".equals(row.kind()) || row.inbound())return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=? AND u.active AND u.role IN ('USER','ADMIN')
                    AND (u.valid_from IS NULL OR u.valid_from<=CURRENT_TIMESTAMP) AND (u.valid_until IS NULL OR u.valid_until>CURRENT_TIMESTAMP)
                    AND NOT EXISTS(SELECT 1 FROM case_ignore_votes v WHERE v.case_id=? AND v.user_id=u.id))
                """,Boolean.class,actor,row.caseId()));
    }
    private boolean successful(Message row) { return Set.of("SENT","DELIVERED").contains(Objects.toString(row.delivery(),"")); }
    private void requireCapability(Message row,String action) {
        if(row.deletedAt()!=null || !successful(row) || !capsAvailable(capability(row),action))throw ApiProblemException.conflict("This provider message cannot be mutated safely.");
    }
    private static boolean capsAvailable(MutationCapability caps,String action) { return action.equals("EDIT")?caps.canEdit():caps.canDelete(); }
    private MutationCapability capability(Message row) {
        if(row.unsafeAttachments())return unavailable("ATTACHMENT_NOT_CLEAN");
        if(!"ENABLED".equals(row.integrationStatus()))return unavailable("INTEGRATION_DISABLED");
        if(row.target()==null)return unavailable("PROVIDER_TARGET_UNAVAILABLE");
        var matching=providers.orderedStream().filter(p->p.supports(row.provider())).toList();
        return matching.size()==1?matching.getFirst().mutationCapability(row.target(),row.attachments(),row.body()!=null&&!row.body().isBlank()):unavailable("PROVIDER_UNAVAILABLE");
    }
    private static MutationCapability unavailable(String reason) { return new MutationCapability(false,false,reason,reason); }
    private Message read(UUID id,boolean lock) {
        return jdbc.query(SELECT+" WHERE m.id=?"+(lock?" FOR UPDATE OF m":""),this::map,id).stream().findFirst().orElseThrow(()->ApiProblemException.notFound("Message was not found."));
    }
    private Message map(ResultSet rs,int n) throws SQLException {
        String ref=rs.getString("provider_message_ref");
        // Historical Slack checkpoints contain an actual ts. Teams never infers an activity ID.
        if(ref==null && "SLACK".equals(rs.getString("provider")))ref=rs.getString("external_message_id");
        MutationTarget target=ref==null?null:new MutationTarget(ref,Objects.toString(rs.getString("conversation_id"),rs.getString("external_conversation_id")),
                rs.getString("sender_id"),rs.getString("service_url"),rs.getString("context_type"),Objects.toString(rs.getString("attachment_payload"),"[]"),
                Objects.toString(rs.getString("credential_ref"),rs.getString("secret_ref")));
        return new Message(rs.getObject("id",UUID.class),rs.getObject("case_id",UUID.class),rs.getObject("author_user_id",UUID.class),rs.getString("kind"),rs.getBoolean("inbound"),
                rs.getString("body"),MessageBodyFormat.valueOf(rs.getString("body_format")),rs.getString("delivery_status"),rs.getString("external_message_id"),rs.getLong("mutation_revision"),
                instant(rs,"deleted_at"),instant(rs,"provider_mutated_at"),IntegrationProvider.valueOf(rs.getString("provider")),rs.getObject("integration_id",UUID.class),
                rs.getString("external_conversation_id"),rs.getString("integration_status"),rs.getString("secret_ref"),rs.getBoolean("has_attachments"),rs.getBoolean("unsafe_attachments"),target);
    }
    private Operation latest(UUID id) { return jdbc.query("SELECT * FROM message_mutations WHERE message_id=? ORDER BY sequence DESC LIMIT 1",this::op,id).stream().findFirst().orElse(null); }
    private Operation active(UUID id) { return jdbc.query("SELECT * FROM message_mutations WHERE message_id=? AND state IN ('QUEUED','RUNNING','RETRY','UNCERTAIN')",this::op,id).stream().findFirst().orElse(null); }
    private Operation operation(UUID id,boolean lock) { return jdbc.query("SELECT * FROM message_mutations WHERE id=?"+(lock?" FOR UPDATE":""),this::op,id).stream().findFirst().orElse(null); }
    private Operation op(ResultSet rs,int n)throws SQLException { return new Operation(rs.getObject("id",UUID.class),rs.getObject("message_id",UUID.class),rs.getObject("actor_user_id",UUID.class),
            rs.getString("action"),rs.getString("requested_body"),rs.getLong("expected_revision"),rs.getString("state"),rs.getInt("attempt_count"),rs.getInt("max_attempts"),
            instant(rs,"retry_until"),instant(rs,"next_attempt_at"),rs.getObject("lease_token",UUID.class),rs.getString("error_code"),rs.getBoolean("retryable"),rs.getString("correlation_id"),instant(rs,"dispatched_at")); }
    private void audit(Message row,UUID actor,String action,UUID operation,String kind) { audit.append(AuditActorType.USER,actor,action,"message",row.id(),row.caseId(),Map.of("operationId",operation.toString(),"mutation",kind)); }
    private void changed(Message row,String correlation) { events.publishUpdated(row.id(),row.caseId(),correlation); }
    private void wakeup(UUID id,Message row,String correlation) { outbox.append("message.mutation.requested","message",row.id(),json.valueToTree(Map.of("operationId",id)).toString(),correlation); changed(row,correlation); }
    private Instant now(){return clock.instant();}
    private static OffsetDateTime at(Instant time){return time.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,String name)throws SQLException { OffsetDateTime value=rs.getObject(name,OffsetDateTime.class);return value==null?null:value.toInstant(); }
    private static String safe(String value){return value!=null&&value.matches("[A-Z][A-Z0-9_]{0,127}")?value:"MUTATION_FAILED";}

    record View(boolean isAuthor,boolean canEdit,boolean canDelete,String editUnavailable,String deleteUnavailable,long revision,
                UUID operationId,String action,String state,String errorCode,boolean canRetry) {}
    record Message(UUID id,UUID caseId,UUID author,String kind,boolean inbound,String body,MessageBodyFormat format,String delivery,String externalRef,long revision,
                   Instant deletedAt,Instant callbackAt,IntegrationProvider provider,UUID integration,String channel,String integrationStatus,String secretRef,boolean attachments,boolean unsafeAttachments,MutationTarget target) {}
    record Operation(UUID id,UUID messageId,UUID actor,String action,String body,long revision,String state,int attempts,int maxAttempts,Instant retryUntil,Instant nextAttempt,UUID lease,String error,boolean retryable,String correlation,Instant dispatchedAt) {}
    record Claim(UUID operation,Message message,UUID actor,String action,String body,long revision,UUID lease,int attempt,int maxAttempts,Instant retryUntil,String correlation) {}
}

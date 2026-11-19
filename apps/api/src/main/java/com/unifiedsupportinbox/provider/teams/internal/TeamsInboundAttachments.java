package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.channel.ChannelIngestionPolicy;
import com.unifiedsupportinbox.integration.IntegrationHealthReporter;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Result;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.ProviderAttachmentMetadata;
import com.unifiedsupportinbox.storage.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

/** Durable per-message enrichment. All provider/object-store/scanner I/O happens outside DB transactions. */
@Component
class TeamsInboundAttachments {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TeamsFilesHttpClient files;
    private final TeamsFilesConfiguration configuration;
    private final ObjectProvider<AttachmentObjectStorage> objects;
    private final ObjectProvider<AttachmentSecurityService> scanner;
    private final AttachmentMetadataCatalog metadata;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;
    private final ChannelIngestionPolicy channels;
    private final IntegrationHealthReporter health;
    TeamsInboundAttachments(JdbcTemplate jdbc,PlatformTransactionManager manager,TeamsFilesHttpClient files,TeamsFilesConfiguration configuration,
            ObjectProvider<AttachmentObjectStorage> objects,ObjectProvider<AttachmentSecurityService> scanner,AttachmentMetadataCatalog metadata,
            OutboxEventStore outbox,ObjectMapper json,ChannelIngestionPolicy channels,IntegrationHealthReporter health) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.files=files;this.configuration=configuration;
        this.objects=objects;this.scanner=scanner;this.metadata=metadata;this.outbox=outbox;this.json=json;this.channels=channels;this.health=health;
    }
    // Called inside the existing inbound processing transaction. No provider calls here.
    void enqueue(Command command,Result result,JsonNode activity) {
        if(!activity.path("attachments").isArray()||activity.path("attachments").isEmpty())return;
        jdbc.update("INSERT INTO teams_attachment_jobs(message_id,inbound_event_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                result.messageId(),command.inboundEventId());
    }
    void processNext() {
        UUID token=UUID.randomUUID();
        Job job=tx.execute(status->jdbc.query("""
                WITH due AS (
                  SELECT message_id FROM teams_attachment_jobs
                  WHERE inbound_event_id IS NOT NULL AND ((status IN ('PENDING','FAILED') AND next_attempt_at<=CURRENT_TIMESTAMP)
                     OR (status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP))
                  ORDER BY created_at,message_id FOR UPDATE SKIP LOCKED LIMIT 1
                ), claimed AS (
                  UPDATE teams_attachment_jobs j SET status='RUNNING',attempts=attempts+1,lease_token=?,
                    lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',updated_at=CURRENT_TIMESTAMP
                  FROM due WHERE j.message_id=due.message_id RETURNING j.*
                )
                SELECT j.message_id,j.inbound_event_id,j.attempts,j.window_started_at AS created_at,c.id AS case_id,c.integration_id,
                  c.external_conversation_id,e.payload_json::text,m.correlation_id
                FROM claimed j JOIN messages m ON m.id=j.message_id JOIN cases c ON c.id=m.case_id
                JOIN inbound_events e ON e.id=j.inbound_event_id
                """,(rs,row)->new Job(rs.getObject("message_id",UUID.class),rs.getObject("case_id",UUID.class),
                    rs.getObject("integration_id",UUID.class),rs.getString("external_conversation_id"),
                    json.readTree(rs.getString("payload_json")),rs.getString("correlation_id"),rs.getInt("attempts"),
                    rs.getObject("created_at",OffsetDateTime.class).toInstant()),token).stream().findFirst().orElse(null));
        if(job==null)return;
        try {
            if(channels.resolve(job.integration(),job.channel()).filter(c->c.active()&&!c.ignored()).isEmpty())
                throw ProviderAttachmentException.validation("TEAMS_FILE_CONTEXT_INACTIVE");
            var sources=files.discover(job.integration(),job.activity());
            if(sources.size()>10)throw ProviderAttachmentException.validation("POLICY_ATTACHMENT_COUNT");
            tx.executeWithoutResult(status->jdbc.update("DELETE FROM attachments WHERE message_id=? AND provider_file_id='teams:discovery' AND storage_key IS NULL",job.message()));
            ProviderAttachmentException firstFailure=null;
            for(var source:sources) {
                if(!renew(job,token))return;
                UUID id=UUID.nameUUIDFromBytes(("teams:"+job.message()+":"+source.key()).getBytes(StandardCharsets.UTF_8));
                var display=new ProviderAttachmentMetadata(source.key(),source.name(),source.type(),0);
                tx.executeWithoutResult(status->{
                    jdbc.update("""
                            INSERT INTO attachments(id,message_id,case_id,original_filename,content_type,size_bytes,provider_file_id)
                            VALUES (?,?,?,?,?,0,?) ON CONFLICT(id) DO NOTHING
                            """,id,job.message(),job.caseId(),display.filename(),display.contentType(),"teams:"+source.key());
                    publish(job);
                });
                try {transfer(job,token,id,source);}
                catch(ProviderAttachmentException failure) {
                    failAttachment(id,failure.errorCode());
                    if(firstFailure==null||failure.retryable())firstFailure=failure;
                }
            }
            finish(job,token,firstFailure);
        }catch(ProviderAttachmentException failure){discoveryFailure(job,token,failure);}
        catch(RuntimeException failure){discoveryFailure(job,token,ProviderAttachmentException.unavailable("TEAMS_FILE_TRANSFER_UNAVAILABLE",null));}
    }
    private boolean renew(Job job,UUID token) {
        return jdbc.update("UPDATE teams_attachment_jobs SET lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes' WHERE message_id=? AND lease_token=? AND status='RUNNING'",job.message(),token)==1;
    }
    private void transfer(Job job,UUID token,UUID id,TeamsFilesHttpClient.Source source) {
        var current=metadata.findById(id).orElseThrow();
        if(current.scanStatus()==AttachmentScanStatus.CLEAN||current.scanStatus()==AttachmentScanStatus.INFECTED)return;
        var store=objects.getIfUnique();var security=scanner.getIfUnique();
        if(store==null||security==null)throw ProviderAttachmentException.unavailable("TEAMS_FILE_STORAGE_UNAVAILABLE",null);
        if(current.storageKey()==null) {
            try(var downloaded=files.download(job.integration(),job.activity(),source,configuration.location(job.integration(),job.channel()))) {
                String key=AttachmentStorageKey.generate();boolean linked=false;
                try(var stream=java.nio.file.Files.newInputStream(downloaded.path())) {
                    store.put(key,stream,downloaded.size(),downloaded.contentType());
                    linked=Boolean.TRUE.equals(tx.execute(status->{
                        jdbc.query("SELECT id FROM messages WHERE id=? FOR UPDATE",rs->{},job.message());
                        if(!renew(job,token))return false;
                        long total=jdbc.queryForObject("SELECT COALESCE(sum(size_bytes),0) FROM attachments WHERE message_id=? AND id<>?",Long.class,job.message(),id);
                        if(total+downloaded.size()>50L*1024*1024)throw ProviderAttachmentException.validation("POLICY_MESSAGE_SIZE");
                        return jdbc.update("""
                                UPDATE attachments SET storage_key=?,sha256=?,original_filename=?,content_type=?,size_bytes=?,
                                  scan_status='PENDING',scan_error=NULL WHERE id=? AND storage_key IS NULL
                                """,key,downloaded.sha256(),downloaded.filename(),downloaded.contentType(),downloaded.size(),id)==1;
                    }));
                }finally{if(!linked)store.delete(key);}
                if(!linked)return;
            }catch(java.io.IOException failure){throw ProviderAttachmentException.unavailable("TEAMS_FILE_STORAGE_UNAVAILABLE",null);}
        }
        try {
            var scanned=security.scan(id);
            if(scanned.scanStatus()==AttachmentScanStatus.ERROR) {
                if(scanned.scanError()!=null&&scanned.scanError().startsWith("POLICY_"))
                    throw ProviderAttachmentException.validation(scanned.scanError());
                throw ProviderAttachmentException.unavailable("TEAMS_FILE_SCAN_UNAVAILABLE",null);
            }
            if(scanned.scanStatus()==AttachmentScanStatus.SCANNING)
                throw ProviderAttachmentException.unavailable("TEAMS_FILE_SCAN_PENDING",null);
        }catch(ProviderAttachmentException failure){throw failure;}
        catch(RuntimeException failure){throw ProviderAttachmentException.unavailable("TEAMS_FILE_SCAN_UNAVAILABLE",null);}
    }
    private void failAttachment(UUID id,String code) {
        jdbc.update("UPDATE attachments SET scan_status='ERROR',scan_error=? WHERE id=? AND scan_status NOT IN ('CLEAN','INFECTED')",code,id);
    }
    private void discoveryFailure(Job job,UUID token,ProviderAttachmentException failure) {
        tx.executeWithoutResult(status->{
            UUID id=UUID.nameUUIDFromBytes(("teams-discovery:"+job.message()).getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO attachments(id,message_id,case_id,original_filename,size_bytes,provider_file_id,scan_status,scan_error)
                    VALUES (?,?,?,'Teams attachments',0,'teams:discovery','ERROR',?) ON CONFLICT(id)
                    DO UPDATE SET scan_status='ERROR',scan_error=EXCLUDED.scan_error
                    """,id,job.message(),job.caseId(),failure.errorCode());
        });
        finish(job,token,failure);
    }
    private void finish(Job job,UUID token,ProviderAttachmentException failure) {
        boolean retry=failure!=null&&failure.retryable()&&job.attempts()<8&&Instant.now().isBefore(job.created().plus(Duration.ofHours(24)));
        Duration backoff=Duration.ofSeconds(Math.min(300,1L<<Math.min(job.attempts(),8)));
        if(failure!=null&&failure.retryAfter()!=null&&failure.retryAfter().compareTo(backoff)>0)backoff=failure.retryAfter();
        Instant next=retry?Instant.now().plus(backoff):null;
        if(next!=null&&next.isAfter(job.created().plus(Duration.ofHours(24))))next=null;
        final Instant due=next;
        tx.executeWithoutResult(status->{
            int changed=jdbc.update("""
                    UPDATE teams_attachment_jobs SET status=?,error_code=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                    WHERE message_id=? AND lease_token=?
                    """,failure==null?"DONE":"FAILED",failure==null?null:failure.errorCode(),due==null?null:OffsetDateTime.ofInstant(due,ZoneOffset.UTC),job.message(),token);
            if(changed==1)publish(job);
        });
        if(failure!=null)health.providerFailure(job.integration(),failure.errorCode(),IntegrationHealthReporter.FailureKind.DEGRADED);
    }
    int retry(UUID integration) {
        return jdbc.update("""
                UPDATE teams_attachment_jobs j SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,
                  window_started_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                FROM messages m JOIN cases c ON c.id=m.case_id
                WHERE j.message_id=m.id AND c.integration_id=? AND j.status='FAILED' AND j.inbound_event_id IS NOT NULL
                  AND j.error_code NOT LIKE 'POLICY_%'
                """,integration);
    }
    private void publish(Job job) {
        outbox.append("message.updated","message",job.message(),json.createObjectNode().put("messageId",job.message().toString()).put("caseId",job.caseId().toString()).toString(),job.correlation());
    }
    private record Job(UUID message,UUID caseId,UUID integration,String channel,JsonNode activity,String correlation,int attempts,Instant created) { }
}

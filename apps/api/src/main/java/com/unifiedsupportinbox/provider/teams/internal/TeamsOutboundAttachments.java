package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.storage.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Upload/checkpoint files before the single customer-visible Connector send. */
@Component
class TeamsOutboundAttachments {
    private final AttachmentMetadataCatalog metadata;
    private final ObjectProvider<AttachmentObjectStorage> objects;
    private final ObjectProvider<AttachmentSecurityService> security;
    private final TeamsFilesHttpClient files;
    private final TeamsFilesConfiguration configuration;
    private final JdbcTemplate jdbc;
    TeamsOutboundAttachments(AttachmentMetadataCatalog metadata,ObjectProvider<AttachmentObjectStorage> objects,
            ObjectProvider<AttachmentSecurityService> security,TeamsFilesHttpClient files,TeamsFilesConfiguration configuration,JdbcTemplate jdbc) {
        this.metadata=metadata;this.objects=objects;this.security=security;this.files=files;this.configuration=configuration;this.jdbc=jdbc;
    }
    List<Map<String,Object>> prepare(DeliveryCommand command) {
        var attachments=metadata.findByMessageId(command.messageId());
        if(attachments.isEmpty())return List.of();
        if(attachments.size()>10)throw ProviderAttachmentException.validation("POLICY_ATTACHMENT_COUNT");
        if(attachments.stream().mapToLong(AttachmentMetadata::sizeBytes).sum()>50L*1024*1024)
            throw ProviderAttachmentException.validation("POLICY_MESSAGE_SIZE");
        var store=objects.getIfUnique();var scanner=security.getIfUnique();
        if(store==null||scanner==null)throw ProviderAttachmentException.unavailable("TEAMS_FILE_STORAGE_UNAVAILABLE",null);
        var location=configuration.location(command.integrationId(),command.externalConversationId());
        var outgoing=new ArrayList<Map<String,Object>>();var cards=new ArrayList<Map<String,Object>>();
        // Small native images fit within the ordinary Teams message payload. Larger images remain original downloadable files.
        int inlineBudget=Math.max(0,20*1024-Objects.toString(command.body(),"").getBytes(StandardCharsets.UTF_8).length);
        for(var attachment:attachments) {
            var clean=scanner.requireClean(attachment.id());
            String type=clean.detectedContentType()==null?clean.contentType():clean.detectedContentType();
            byte[] image=smallInlineImage(clean,type,store,inlineBudget);
            if(image!=null) {
                String encoded=Base64.getEncoder().encodeToString(image);inlineBudget-=encoded.length()+512;
                outgoing.add(Map.of("contentType",type,"name",clean.originalFilename(),"contentUrl","data:"+type+";base64,"+encoded));
                continue;
            }
            var checkpoint=jdbc.query("SELECT drive_id,item_id FROM teams_outbound_files WHERE attachment_id=?",
                    (rs,row)->new String[]{rs.getString(1),rs.getString(2)},clean.id()).stream().findFirst().orElse(null);
            TeamsFilesHttpClient.StoredFile remote;
            if(checkpoint!=null)remote=files.stored(command.integrationId(),checkpoint[0],checkpoint[1],clean.originalFilename(),location);
            else {
                String name="usi-"+command.messageId()+"-"+clean.id()+extension(clean.originalFilename());
                remote=files.upload(command.integrationId(),location,name,clean.originalFilename(),type,clean.sizeBytes(),()->store.open(clean.storageKey()));
                jdbc.update("INSERT INTO teams_outbound_files(attachment_id,drive_id,item_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",clean.id(),remote.driveId(),remote.itemId());
                metadata.recordProviderFileId(clean.id(),"teams:"+remote.itemId());
            }
            String label=clean.originalFilename().replace("\\","\\\\").replace("[","\\[").replace("]","\\]");
            String url=remote.webUrl().replace("(","%28").replace(")","%29");
            cards.add(Map.of("type","TextBlock","text","📎 ["+label+"]("+url+")","wrap",true));
        }
        if(!cards.isEmpty())outgoing.add(Map.of("contentType","application/vnd.microsoft.card.adaptive","content",
                Map.of("type","AdaptiveCard","version","1.4","body",cards)));
        return List.copyOf(outgoing);
    }
    private static String extension(String name) {
        int dot=name.lastIndexOf('.');String suffix=dot<0?"":name.substring(dot);
        return suffix.matches("\\.[A-Za-z0-9]{1,12}")?suffix:"";
    }
    private static byte[] smallInlineImage(AttachmentMetadata file,String type,AttachmentObjectStorage store,int budget) {
        if(!Set.of("image/png","image/jpeg","image/gif").contains(type)||file.sizeBytes()>12*1024||file.sizeBytes()*4/3+512>budget)return null;
        try(var input=store.open(file.storageKey())) {
            byte[] bytes=input.readNBytes(12*1024+1);
            if(bytes.length!=file.sizeBytes()||bytes.length>12*1024)return null;
            try(var image=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
                var readers=javax.imageio.ImageIO.getImageReaders(image);if(!readers.hasNext())return null;
                var reader=readers.next();try {
                    reader.setInput(image);if(reader.getWidth(0)>1024||reader.getHeight(0)>1024||reader.getNumImages(true)>1)return null;
                }finally{reader.dispose();}
            }
            return bytes;
        }catch(java.io.IOException failure){throw ProviderAttachmentException.unavailable("TEAMS_FILE_STORAGE_UNAVAILABLE",null);}
    }
}

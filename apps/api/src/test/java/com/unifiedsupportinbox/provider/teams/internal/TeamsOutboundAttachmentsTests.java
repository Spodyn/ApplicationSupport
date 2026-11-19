package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.*;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.storage.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class TeamsOutboundAttachmentsTests {
    final AttachmentMetadataCatalog metadata=mock(AttachmentMetadataCatalog.class);
    final AttachmentObjectStorage objects=mock(AttachmentObjectStorage.class);
    final AttachmentSecurityService security=mock(AttachmentSecurityService.class);
    final TeamsFilesHttpClient files=mock(TeamsFilesHttpClient.class);
    final TeamsFilesConfiguration configuration=mock(TeamsFilesConfiguration.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final MessageDeliveryProvider.DeliveryCommand command=new MessageDeliveryProvider.DeliveryCommand(UUID.randomUUID(),"stable",UUID.randomUUID(),
            IntegrationProvider.TEAMS,UUID.randomUUID(),"channel","root","reply",MessageBodyFormat.PLAIN_TEXT,"test");
    final TeamsFilesHttpClient.FileLocation location=new TeamsFilesHttpClient.FileLocation("drive","folder",Set.of("sandbox.sharepoint.com"));
    @SuppressWarnings("unchecked") TeamsOutboundAttachments service() {
        ObjectProvider<AttachmentObjectStorage> objectProvider=mock(ObjectProvider.class);
        ObjectProvider<AttachmentSecurityService> securityProvider=mock(ObjectProvider.class);
        when(objectProvider.getIfUnique()).thenReturn(objects);when(securityProvider.getIfUnique()).thenReturn(security);
        when(configuration.location(command.integrationId(),"channel")).thenReturn(location);
        return new TeamsOutboundAttachments(metadata,objectProvider,securityProvider,files,configuration,jdbc);
    }
    AttachmentMetadata attachment(String name,String type,long size) {
        var file=new AttachmentMetadata(UUID.randomUUID(),command.messageId(),"object-key",name,type,type,size,"hash",AttachmentScanStatus.CLEAN,null,null,Instant.now());
        when(security.requireClean(file.id())).thenReturn(file);return file;
    }
    @Test void mixedFilesKeepTheirNamesAndUseStableUploadsAndCheckpointsOnRetry() {
        var pdf=attachment("evidence.pdf","application/pdf",100);
        var doc=attachment("evidence.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",200);
        when(metadata.findByMessageId(command.messageId())).thenReturn(List.of(pdf,doc));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String[]>>any(),any(Object[].class)))
                .thenReturn(List.of());
        when(files.upload(eq(command.integrationId()),eq(location),anyString(),anyString(),anyString(),anyLong(),any()))
                .thenAnswer(call->new TeamsFilesHttpClient.StoredFile("drive",call.getArgument(2),"https://sandbox.sharepoint.com/sites/test/"+call.getArgument(2),call.getArgument(3)));
        var service=service();var result=service.prepare(command);
        assertThat(result).hasSize(1);assertThat(result.toString()).contains("evidence.pdf","evidence.docx","application/vnd.microsoft.card.adaptive").doesNotContain("localhost","object-key");
        verify(files).upload(eq(command.integrationId()),eq(location),eq("usi-"+command.messageId()+"-"+pdf.id()+".pdf"),eq("evidence.pdf"),eq("application/pdf"),eq(100L),any());
        verify(metadata).recordProviderFileId(eq(pdf.id()),startsWith("teams:"));
        reset(files);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String[]>>any(),any(Object[].class)))
                .thenReturn(Collections.singletonList(new String[]{"drive","existing"}));
        when(files.stored(eq(command.integrationId()),eq("drive"),eq("existing"),anyString(),eq(location)))
                .thenAnswer(call->new TeamsFilesHttpClient.StoredFile("drive","existing","https://sandbox.sharepoint.com/sites/test/existing",call.getArgument(3)));
        assertThat(service.prepare(command)).hasSize(1);
        verify(files,never()).upload(any(),any(),anyString(),anyString(),anyString(),anyLong(),any());
        verify(files,times(2)).stored(eq(command.integrationId()),eq("drive"),eq("existing"),anyString(),eq(location));
    }
    @Test void smallImageUsesNativeImageAttachmentWithoutGrantOrSharePointUpload() throws Exception {
        var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var image=attachment("image.png","image/png",bytes.size());when(objects.open("object-key")).thenReturn(new ByteArrayInputStream(bytes.toByteArray()));
        when(metadata.findByMessageId(command.messageId())).thenReturn(List.of(image));
        var result=service().prepare(command);
        assertThat(result).singleElement().satisfies(a->{assertThat(a.get("contentType")).isEqualTo("image/png");assertThat(a.get("contentUrl").toString()).startsWith("data:image/png;base64,");});
        verifyNoInteractions(files);verify(security).requireClean(image.id());
    }
    @Test void quarantineAndPermissionsFailBeforeAnyCustomerVisibleSend() {
        var pdf=attachment("evidence.pdf","application/pdf",100);when(metadata.findByMessageId(command.messageId())).thenReturn(List.of(pdf));
        when(security.requireClean(pdf.id())).thenThrow(ProviderAttachmentException.validation("POLICY_ATTACHMENT_QUARANTINED"));
        assertThatThrownBy(()->service().prepare(command)).isInstanceOf(ProviderAttachmentException.class);
        verifyNoInteractions(files,objects);
    }
}

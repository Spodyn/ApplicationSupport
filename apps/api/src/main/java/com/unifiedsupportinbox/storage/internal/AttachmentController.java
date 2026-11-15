package com.unifiedsupportinbox.storage.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/cases")
@ConditionalOnProperty(name = {
        "usi.object-storage.access-key",
        "usi.object-storage.secret-key"
})
class AttachmentController {

    private final SecureAttachmentService attachments;

    AttachmentController(SecureAttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(value = "/{caseId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AttachmentView> upload(
            @PathVariable UUID caseId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        UUID userId = authenticatedUserId(authentication);
        AttachmentMetadata uploaded = attachments.upload(caseId, userId, file);
        return ResponseEntity.status(201).body(AttachmentView.from(uploaded));
    }

    @PostMapping("/{caseId}/attachments/{attachmentId}/scan")
    AttachmentView retryScan(@PathVariable UUID caseId, @PathVariable UUID attachmentId, Authentication authentication) {
        return AttachmentView.from(attachments.retryScan(caseId, authenticatedUserId(authentication), attachmentId));
    }

    @GetMapping("/{caseId}/attachments/{attachmentId}")
    ResponseEntity<InputStreamResource> download(
            @PathVariable UUID caseId, @PathVariable UUID attachmentId, Authentication authentication) {
        return serve(caseId, attachmentId, authenticatedUserId(authentication), false);
    }

    @GetMapping("/{caseId}/attachments/{attachmentId}/preview")
    ResponseEntity<InputStreamResource> preview(
            @PathVariable UUID caseId, @PathVariable UUID attachmentId, Authentication authentication) {
        return serve(caseId, attachmentId, authenticatedUserId(authentication), true);
    }

    private ResponseEntity<InputStreamResource> serve(UUID caseId, UUID attachmentId, UUID viewer, boolean inline) {
        SecureAttachmentService.Download download;
        try {
            download = attachments.openForRead(caseId, attachmentId, viewer, inline);
        } catch (com.unifiedsupportinbox.storage.AttachmentQuarantinedException quarantined) {
            throw ApiProblemException.conflict("The attachment is quarantined and cannot be opened.");
        }
        AttachmentMetadata metadata = download.metadata();
        MediaType type = mediaType(metadata.detectedContentType());
        ContentDisposition.Builder disposition = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        InputStream content = download.content();
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(metadata.sizeBytes())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition.filename(metadata.originalFilename(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(content));
    }

    @DeleteMapping("/{caseId}/attachments/{attachmentId}")
    ResponseEntity<Void> removePending(
            @PathVariable UUID caseId,
            @PathVariable UUID attachmentId,
            Authentication authentication) {
        attachments.removePending(caseId, authenticatedUserId(authentication), attachmentId);
        return ResponseEntity.noContent().build();
    }

    private static UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiProblemException.authenticationRequired();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.authenticationRequired();
        }
    }

    private static MediaType mediaType(String detected) {
        return detected == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(detected);
    }

    record AttachmentView(
            UUID attachmentId,
            String filename,
            String contentType,
            long sizeBytes,
            String scanStatus,
            String scanError) {

        static AttachmentView from(AttachmentMetadata metadata) {
            return new AttachmentView(
                    metadata.id(),
                    metadata.originalFilename(),
                    metadata.detectedContentType() == null ? metadata.contentType() : metadata.detectedContentType(),
                    metadata.sizeBytes(),
                    metadata.scanStatus().name(),
                    metadata.scanError());
        }
    }
}

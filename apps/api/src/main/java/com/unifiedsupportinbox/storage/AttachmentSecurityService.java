package com.unifiedsupportinbox.storage;

import java.util.UUID;

/**
 * Security boundary for locally stored attachment bytes. An attachment is usable by normal
 * download/provider flows only after this service has produced a CLEAN scan state.
 */
public interface AttachmentSecurityService {

    AttachmentMetadata scan(UUID attachmentId);

    AttachmentMetadata requireClean(UUID attachmentId);
}

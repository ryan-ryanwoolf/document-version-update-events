package com.ryanwoolf.document_version_update_events.processor;

import com.ryanwoolf.document_version_update_events.service.DocumentRevisionService;
import org.springframework.stereotype.Service;

@Service
public class PendingRevisionProcessor {

    private final DocumentRevisionService documentRevisionService;

    public PendingRevisionProcessor(DocumentRevisionService documentRevisionService) {
        this.documentRevisionService = documentRevisionService;
    }

    public void processPendingForDocument(String documentId) {
        documentRevisionService.reconcilePendingForDocument(documentId);
    }

}

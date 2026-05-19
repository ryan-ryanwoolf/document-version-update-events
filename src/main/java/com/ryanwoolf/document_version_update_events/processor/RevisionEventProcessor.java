package com.ryanwoolf.document_version_update_events.processor;

import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import com.ryanwoolf.document_version_update_events.service.DocumentRevisionService;
import org.springframework.stereotype.Service;

@Service
public class RevisionEventProcessor {

    private final DocumentRevisionService documentRevisionService;

    public RevisionEventProcessor(DocumentRevisionService documentRevisionService) {
        this.documentRevisionService = documentRevisionService;
    }

    public void process(RevisionEvent revisionEvent) {
        documentRevisionService.processRevisionEvent(revisionEvent);
    }

}

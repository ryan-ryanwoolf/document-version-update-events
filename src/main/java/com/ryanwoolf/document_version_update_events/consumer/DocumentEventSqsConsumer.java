package com.ryanwoolf.document_version_update_events.consumer;

import com.ryanwoolf.document_version_update_events.config.DocumentProcessingStripes;
import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
@Profile("aws")
public class DocumentEventSqsConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventSqsConsumer.class);

    private final RevisionEventHandler revisionEventHandler;
    private final DocumentProcessingStripes documentProcessingStripes;

    public DocumentEventSqsConsumer(
            RevisionEventHandler revisionEventHandler,
            DocumentProcessingStripes documentProcessingStripes) {
        this.revisionEventHandler = revisionEventHandler;
        this.documentProcessingStripes = documentProcessingStripes;
    }

    /**
     * Failures propagate so the message is not acknowledged and SQS redelivers after visibility timeout
     * (configure queue visibility timeout and DLQ maxReceiveCount in AWS).
     */
    @SqsListener("${app.sqs.revision-events-queue-url}")
    public void onRevisionEvent(
            RevisionEvent revisionEvent,
            @Header(name = "Sqs_MSA_ApproximateReceiveCount", required = false) String approximateReceiveCount) {
        if (isSqsRedelivery(approximateReceiveCount)) {
            log.info(
                    "SQS redelivery: ApproximateReceiveCount={} eventId={} documentId={}",
                    approximateReceiveCount,
                    revisionEvent.eventId(),
                    revisionEvent.documentId());
        }
        documentProcessingStripes.withLock(
                revisionEvent.documentId(),
                () -> revisionEventHandler.handle(revisionEvent));
    }

    private static boolean isSqsRedelivery(String approximateReceiveCount) {
        if (approximateReceiveCount == null || approximateReceiveCount.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(approximateReceiveCount.trim()) > 1;
        } catch (NumberFormatException numberFormatException) {
            return false;
        }
    }
}

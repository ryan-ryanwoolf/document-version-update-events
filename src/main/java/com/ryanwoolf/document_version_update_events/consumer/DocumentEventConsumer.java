package com.ryanwoolf.document_version_update_events.consumer;

import com.ryanwoolf.document_version_update_events.config.DocumentProcessingStripes;
import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.support.JmsHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
public class DocumentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventConsumer.class);

    private final RevisionEventHandler revisionEventHandler;
    private final DocumentProcessingStripes documentProcessingStripes;

    public DocumentEventConsumer(
            RevisionEventHandler revisionEventHandler,
            DocumentProcessingStripes documentProcessingStripes) {
        this.revisionEventHandler = revisionEventHandler;
        this.documentProcessingStripes = documentProcessingStripes;
    }

    /**
     * Optional JMS headers make <em>broker</em> redelivery visible (after a transacted listener rolls back).
     * ActiveMQ sets {@code jms_redelivered} / {@code JMSXDeliveryCount}; names differ by broker but both are optional.
     */
    @JmsListener(
            destination = "${app.jms.revision-events-destination}",
            containerFactory = "jmsListenerContainerFactory")
    public void onRevisionEvent(
            RevisionEvent revisionEvent,
            @Header(name = JmsHeaders.REDELIVERED, required = false) Boolean jmsRedelivered,
            @Header(name = "JMSXDeliveryCount", required = false) Integer jmsxDeliveryCount) {
        if (isBrokerRedelivery(jmsRedelivered, jmsxDeliveryCount)) {
            log.info(
                    "JMS broker redelivery: jms_redelivered={} JMSXDeliveryCount={} eventId={} documentId={}",
                    jmsRedelivered,
                    jmsxDeliveryCount,
                    revisionEvent.eventId(),
                    revisionEvent.documentId());
        }
        documentProcessingStripes.withLock(
                revisionEvent.documentId(),
                () -> revisionEventHandler.handle(revisionEvent));
    }

    private static boolean isBrokerRedelivery(Boolean jmsRedelivered, Integer jmsxDeliveryCount) {
        if (Boolean.TRUE.equals(jmsRedelivered)) {
            return true;
        }
        return jmsxDeliveryCount != null && jmsxDeliveryCount > 1;
    }
}

package com.ryanwoolf.document_version_update_events.messaging;

import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!aws")
public class JmsRevisionEventPublisher implements RevisionEventPublisher {

    private final JmsTemplate jmsTemplate;
    private final String revisionEventsDestination;

    public JmsRevisionEventPublisher(
            JmsTemplate jmsTemplate,
            @Value("${app.jms.revision-events-destination}") String revisionEventsDestination) {
        this.jmsTemplate = jmsTemplate;
        this.revisionEventsDestination = revisionEventsDestination;
    }

    @Override
    public void publish(RevisionEvent revisionEvent) {
        jmsTemplate.convertAndSend(revisionEventsDestination, revisionEvent);
    }
}

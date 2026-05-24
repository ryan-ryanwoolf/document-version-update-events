package com.ryanwoolf.document_version_update_events.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("aws")
public class SqsRevisionEventPublisher implements RevisionEventPublisher {

    private final SqsTemplate sqsTemplate;
    private final String revisionEventsQueueUrl;
    private final ObjectMapper revisionEventObjectMapper;

    public SqsRevisionEventPublisher(
            SqsTemplate sqsTemplate,
            @Value("${app.sqs.revision-events-queue-url}") String revisionEventsQueueUrl,
            ObjectMapper revisionEventObjectMapper) {
        this.sqsTemplate = sqsTemplate;
        this.revisionEventsQueueUrl = revisionEventsQueueUrl;
        this.revisionEventObjectMapper = revisionEventObjectMapper;
    }

    @Override
    public void publish(RevisionEvent revisionEvent) {
        try {
            String body = revisionEventObjectMapper.writeValueAsString(revisionEvent);
            sqsTemplate.send(to -> to.queue(revisionEventsQueueUrl).payload(body));
        } catch (JsonProcessingException jsonProcessingException) {
            throw new IllegalStateException("Failed to serialize revision event for SQS", jsonProcessingException);
        }
    }
}

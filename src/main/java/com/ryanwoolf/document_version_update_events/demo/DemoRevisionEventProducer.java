package com.ryanwoolf.document_version_update_events.demo;

import com.ryanwoolf.document_version_update_events.model.RevisionDocumentData;
import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import com.ryanwoolf.document_version_update_events.model.RevisionEventType;
import com.ryanwoolf.document_version_update_events.repository.DocumentRepository;
import com.ryanwoolf.document_version_update_events.repository.PendingDocumentRevisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DemoRevisionEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DemoRevisionEventProducer.class);

    private static final String DOC_HOT = "doc-hot-001";
    private static final String DOC_MEDIUM = "doc-medium-001";
    private static final String DOC_SMALL = "doc-small-001";
    private static final String DOC_GAP = "doc-gap-001";
    /**
     * Document id for a broken upstream: one sequence number is never published, so {@code document_state}
     * stops advancing and one or more rows sit in {@code pending_revision_event} indefinitely (until an admin
     * or replay supplies the gap).
     */
    private static final String DOC_STUCK_MISSING_REVISION = "doc-stuck-missing-revision-001";

    private final JmsTemplate jmsTemplate;
    private final String revisionEventsDestination;
    private final String demoRetryVerificationEventIdPrefix;
    private final DocumentRepository documentRepository;
    private final PendingDocumentRevisionRepository pendingDocumentRevisionRepository;
    private final ExecutorService demoExecutorService;

    public DemoRevisionEventProducer(
            JmsTemplate jmsTemplate,
            @Value("${app.jms.revision-events-destination}") String revisionEventsDestination,
            @Value("${app.demo.retry-verification.event-id-prefix:retry-verify-}") String demoRetryVerificationEventIdPrefix,
            DocumentRepository documentRepository,
            PendingDocumentRevisionRepository pendingDocumentRevisionRepository,
            ExecutorService demoExecutorService) {
        this.jmsTemplate = jmsTemplate;
        this.revisionEventsDestination = revisionEventsDestination;
        this.demoRetryVerificationEventIdPrefix = demoRetryVerificationEventIdPrefix;
        this.documentRepository = documentRepository;
        this.pendingDocumentRevisionRepository = pendingDocumentRevisionRepository;
        this.demoExecutorService = demoExecutorService;
    }

    @Transactional
    public int startDemo() {
        clearDemoState();

        Map<String, List<RevisionEvent>> eventsByDocument = new LinkedHashMap<>();
        eventsByDocument.put(DOC_HOT, applyHotDocumentOrdering(generateDocumentEvents(DOC_HOT, 50)));
        eventsByDocument.put(DOC_MEDIUM, applyMediumDocumentDuplicates(generateDocumentEvents(DOC_MEDIUM, 30)));
        eventsByDocument.put(DOC_SMALL, generateDocumentEvents(DOC_SMALL, 10));
        eventsByDocument.put(DOC_GAP, applyGapDocumentOrdering(generateDocumentEvents(DOC_GAP, 15)));
        // Sequence 2 is never enqueued: after CREATE (1) applies, revision 3+ buffer forever — see SOLUTION.md.
        eventsByDocument.put(
                DOC_STUCK_MISSING_REVISION,
                generateDocumentEventsSkippingSequence(DOC_STUCK_MISSING_REVISION, 12, 2L));

        List<RevisionEvent> productionLikeOrder = interleaveDocuments(eventsByDocument);

        demoExecutorService.submit(() -> publishEvents(productionLikeOrder));

        log.info(
                "Demo revision event run scheduled with {} events (publishing to JMS destination={})",
                productionLikeOrder.size(),
                revisionEventsDestination);
        return productionLikeOrder.size();
    }

    /**
     * Send a single event to the revision queue (same destination consumers use), e.g. a missing sequence
     * for the stuck demo document {@link #DOC_STUCK_MISSING_REVISION}.
     */
    public void publishOne(RevisionEvent revisionEvent) {
        jmsTemplate.convertAndSend(revisionEventsDestination, revisionEvent);
        log.info(
                "Published single revision event to destination={} documentId={} sequence={} eventId={}",
                revisionEventsDestination,
                revisionEvent.documentId(),
                revisionEvent.sequence(),
                revisionEvent.eventId());
    }

    /**
     * Enqueues one CREATE (sequence 1) for a fresh document id, with an {@code event_id} that triggers
     * {@code app.demo.retry-verification.failures-before-success} simulated failures (transacted rollback + broker
     * redelivery each time), then applies on the next delivery — see {@link com.ryanwoolf.document_version_update_events.consumer.RevisionEventHandler}.
     */
    public RevisionEvent publishRetryVerificationEvent() {
        String documentId = "doc-retry-verify-" + UUID.randomUUID().toString().substring(0, 8);
        RevisionEvent revisionEvent = new RevisionEvent(
                demoRetryVerificationEventIdPrefix + UUID.randomUUID(),
                documentId,
                1L,
                RevisionEventType.CREATE,
                Instant.now(),
                new RevisionDocumentData(
                        "Retry verification " + documentId,
                        "Event for JMS rollback / broker redelivery demo"));
        publishOne(revisionEvent);
        return revisionEvent;
    }

    private void clearDemoState() {
        documentRepository.deleteAll();
        pendingDocumentRevisionRepository.deleteAll();
        log.info("Cleared document_state and pending_revision_event");
    }

    private void publishEvents(List<RevisionEvent> events) {
        for (RevisionEvent event : events) {
            jmsTemplate.convertAndSend(revisionEventsDestination, event);
            sleepRandomly();
        }
        log.info(
                "Demo revision event run completed ({} messages sent to {})",
                events.size(),
                revisionEventsDestination);
    }

    private List<RevisionEvent> generateDocumentEvents(String documentId, int count) {
        List<RevisionEvent> events = new ArrayList<>(count);
        for (long sequence = 1; sequence <= count; sequence++) {
            events.add(buildEvent(documentId, sequence));
        }
        return events;
    }

    /** Inclusive {@code maxSequence}; omits {@code skipSequence} (e.g. never-published revision). */
    private List<RevisionEvent> generateDocumentEventsSkippingSequence(
            String documentId, int maxSequence, long skipSequence) {
        List<RevisionEvent> events = new ArrayList<>(maxSequence);
        for (long sequence = 1; sequence <= maxSequence; sequence++) {
            if (sequence == skipSequence) {
                continue;
            }
            events.add(buildEvent(documentId, sequence));
        }
        return events;
    }

    private RevisionEvent buildEvent(String documentId, long sequence) {
        RevisionEventType eventType =
                sequence == 1L ? RevisionEventType.CREATE : RevisionEventType.UPDATE;
        return new RevisionEvent(
                UUID.randomUUID().toString(),
                documentId,
                sequence,
                eventType,
                Instant.now(),
                new RevisionDocumentData(
                        "%s revision %d".formatted(documentId, sequence),
                        "Body for %s at sequence %d".formatted(documentId, sequence)));
    }

    private List<RevisionEvent> applyHotDocumentOrdering(List<RevisionEvent> orderedEvents) {
        List<RevisionEvent> reorderedEvents = new ArrayList<>(orderedEvents);
        for (int index = 3; index < reorderedEvents.size() - 1; index += 4) {
            RevisionEvent laterEvent = reorderedEvents.get(index + 1);
            reorderedEvents.set(index + 1, reorderedEvents.get(index));
            reorderedEvents.set(index, laterEvent);
        }
        return reorderedEvents;
    }

    private List<RevisionEvent> applyMediumDocumentDuplicates(List<RevisionEvent> orderedEvents) {
        List<RevisionEvent> eventsWithDuplicate = new ArrayList<>(orderedEvents);
        RevisionEvent sequenceTwoEvent = orderedEvents.get(1);
        eventsWithDuplicate.add(2, sequenceTwoEvent);
        return eventsWithDuplicate;
    }

    private List<RevisionEvent> applyGapDocumentOrdering(List<RevisionEvent> orderedEvents) {
        if (orderedEvents.size() < 5) {
            return orderedEvents;
        }

        List<RevisionEvent> reorderedEvents = new ArrayList<>();
        int[] leadingOrder = {0, 3, 1, 2, 4};
        for (int index : leadingOrder) {
            reorderedEvents.add(orderedEvents.get(index));
        }
        for (int index = 5; index < orderedEvents.size(); index++) {
            reorderedEvents.add(orderedEvents.get(index));
        }
        return reorderedEvents;
    }

    private List<RevisionEvent> interleaveDocuments(Map<String, List<RevisionEvent>> eventsByDocument) {
        List<Deque<RevisionEvent>> documentQueues = new ArrayList<>();
        for (List<RevisionEvent> documentEvents : eventsByDocument.values()) {
            documentQueues.add(new ArrayDeque<>(documentEvents));
        }

        List<RevisionEvent> interleavedEvents = new ArrayList<>();
        boolean eventAdded;
        do {
            eventAdded = false;
            for (Deque<RevisionEvent> documentQueue : documentQueues) {
                if (!documentQueue.isEmpty()) {
                    interleavedEvents.add(documentQueue.removeFirst());
                    eventAdded = true;
                }
            }
        } while (eventAdded);

        return interleavedEvents;
    }

    private void sleepRandomly() {
        try {

            int randomOneToTen = (int) Math.floor(Math.random() * 10) + 1;
            if(randomOneToTen > 5){
                Thread.sleep(ThreadLocalRandom.current().nextInt(10, 101));
            }

        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo event publishing interrupted", interruptedException);
        }
    }

}

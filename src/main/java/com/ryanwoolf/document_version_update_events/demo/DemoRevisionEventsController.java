package com.ryanwoolf.document_version_update_events.demo;

import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/demo/revision-events")
@Validated
@ConditionalOnProperty(name = "app.demo.endpoints.enabled", havingValue = "true")
public class DemoRevisionEventsController {

    private final DemoRevisionEventProducer demoRevisionEventProducer;
    private final boolean demoRetryVerificationEnabled;
    private final long jmsRedeliveryInitialDelayMs;
    private final int demoRetryVerificationFailuresBeforeSuccess;

    public DemoRevisionEventsController(
            DemoRevisionEventProducer demoRevisionEventProducer,
            @Value("${app.demo.retry-verification.enabled:false}") boolean demoRetryVerificationEnabled,
            @Value("${app.jms.redelivery.initial-delay-ms:5000}") long jmsRedeliveryInitialDelayMs,
            @Value("${app.demo.retry-verification.failures-before-success:1}") int demoRetryVerificationFailuresBeforeSuccess) {
        this.demoRevisionEventProducer = demoRevisionEventProducer;
        this.demoRetryVerificationEnabled = demoRetryVerificationEnabled;
        this.jmsRedeliveryInitialDelayMs = jmsRedeliveryInitialDelayMs;
        this.demoRetryVerificationFailuresBeforeSuccess = Math.max(0, demoRetryVerificationFailuresBeforeSuccess);
    }

    /**
     * Accepts spec-shaped JSON and enqueues it on {@code document.revision.events} so any instance can consume it.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> publish(@RequestBody @Valid RevisionEvent revisionEvent) {
        demoRevisionEventProducer.publishOne(revisionEvent);
        return ResponseEntity.accepted()
                .body(Map.of(
                        "status", "queued",
                        "eventId", revisionEvent.eventId(),
                        "documentId", revisionEvent.documentId(),
                        "sequence", revisionEvent.sequence(),
                        "message",
                        "Revision event sent to the JMS queue; consumers handle it like any broker delivery."));
    }

    @PostMapping("/retry-verification")
    public ResponseEntity<Map<String, Object>> retryVerification() {
        if (!demoRetryVerificationEnabled) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            "app.demo.retry-verification.enabled is false — set to true, restart, then POST again",
                            "alternative",
                            "POST /demo/revision-events with JSON body whose event_id starts with retry-verify- (see app.demo.retry-verification.event-id-prefix)"));
        }
        RevisionEvent revisionEvent = demoRevisionEventProducer.publishRetryVerificationEvent();
        int n = demoRetryVerificationFailuresBeforeSuccess;
        String failPhrase =
                n == 1 ? "The first listener delivery throws" : "The first " + n + " listener deliveries throw";
        return ResponseEntity.accepted()
                .body(Map.of(
                        "status", "queued",
                        "eventId", revisionEvent.eventId(),
                        "documentId", revisionEvent.documentId(),
                        "sequence", revisionEvent.sequence(),
                        "failuresBeforeSuccess", n,
                        "message",
                        failPhrase
                                + " (demo); each rolls back the JMS session; broker redelivery with ~"
                                + jmsRedeliveryInitialDelayMs
                                + "ms initial delay (app.jms.redelivery.*, then exponential). Delivery "
                                + (n + 1)
                                + " applies. Watch logs: WARN simulated failures, INFO broker redelivery, INFO applying."));
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        int totalEvents = demoRevisionEventProducer.startDemo();
        return ResponseEntity.accepted()
                .body(Map.of(
                        "status", "started",
                        "totalEvents", totalEvents,
                        "message",
                        "Publishing demo revision events to the JMS queue (competing consumers across instances)"));
    }

}

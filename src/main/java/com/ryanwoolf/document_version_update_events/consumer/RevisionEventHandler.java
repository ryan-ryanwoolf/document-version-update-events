package com.ryanwoolf.document_version_update_events.consumer;

import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import com.ryanwoolf.document_version_update_events.processor.RevisionEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class RevisionEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RevisionEventHandler.class);

    /**
     * Per {@code event_id}, how many times {@link #handle} ran for demo retry verification (across JMS broker redeliveries).
     */
    private static final ConcurrentHashMap<String, Integer> DEMO_RETRY_VERIFICATION_INVOCATIONS =
            new ConcurrentHashMap<>();

    private final RevisionEventProcessor revisionEventProcessor;
    private final boolean demoRetryVerificationEnabled;
    private final String demoRetryVerificationEventIdPrefix;
    private final int demoRetryVerificationFailuresBeforeSuccess;

    public RevisionEventHandler(
            RevisionEventProcessor revisionEventProcessor,
            @Value("${app.demo.retry-verification.enabled:false}") boolean demoRetryVerificationEnabled,
            @Value("${app.demo.retry-verification.event-id-prefix:retry-verify-}") String demoRetryVerificationEventIdPrefix,
            @Value("${app.demo.retry-verification.failures-before-success:1}") int demoRetryVerificationFailuresBeforeSuccess) {
        this.revisionEventProcessor = revisionEventProcessor;
        this.demoRetryVerificationEnabled = demoRetryVerificationEnabled;
        this.demoRetryVerificationEventIdPrefix = demoRetryVerificationEventIdPrefix;
        this.demoRetryVerificationFailuresBeforeSuccess = Math.max(0, demoRetryVerificationFailuresBeforeSuccess);
    }

    /**
     * No in-process retry ladder: a failure rolls back the transacted JMS session so the <em>broker</em> redelivers
     * (ActiveMQ redelivery policy, SQS visibility timeout, etc.). Tune broker knobs for backoff instead of stacking
     * fast in-thread retries on an overloaded database.
     */
    public void handle(RevisionEvent revisionEvent) {
        maybeFailDemoRetryVerification(revisionEvent);
        revisionEventProcessor.process(revisionEvent);
    }

    private void maybeFailDemoRetryVerification(RevisionEvent revisionEvent) {
        if (!demoRetryVerificationEnabled
                || !revisionEvent.eventId().startsWith(demoRetryVerificationEventIdPrefix)
                || demoRetryVerificationFailuresBeforeSuccess == 0) {
            return;
        }
        int invocation = DEMO_RETRY_VERIFICATION_INVOCATIONS.merge(revisionEvent.eventId(), 1, Integer::sum);
        int failuresBeforeSuccess = demoRetryVerificationFailuresBeforeSuccess;
        int successDelivery = failuresBeforeSuccess + 1;
        if (invocation <= failuresBeforeSuccess) {
            log.warn(
                    "Demo retry verification: simulated failure (listener delivery {}/{}, then broker redelivery;"
                            + " eventId={} — JMS session rolls back until delivery {})",
                    invocation,
                    successDelivery,
                    revisionEvent.eventId(),
                    successDelivery);
            throw new IllegalStateException(
                    "Simulated transient failure (app.demo.retry-verification.failures-before-success="
                            + failuresBeforeSuccess
                            + ")");
        }
        if (invocation == successDelivery) {
            log.info(
                    "Demo retry verification: listener delivery {} of {} — applying after {} simulated failure(s);"
                            + " eventId={}",
                    invocation,
                    successDelivery,
                    failuresBeforeSuccess,
                    revisionEvent.eventId());
        }
    }
}

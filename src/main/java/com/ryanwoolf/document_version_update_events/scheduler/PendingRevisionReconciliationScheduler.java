package com.ryanwoolf.document_version_update_events.scheduler;

import com.ryanwoolf.document_version_update_events.config.PendingRevisionExecutorConfig;
import com.ryanwoolf.document_version_update_events.processor.PendingRevisionProcessor;
import com.ryanwoolf.document_version_update_events.repository.PendingDocumentRevisionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PendingRevisionReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingRevisionReconciliationScheduler.class);

    private final PendingDocumentRevisionRepository pendingDocumentRevisionRepository;
    private final PendingRevisionProcessor pendingRevisionProcessor;
    private final ThreadPoolTaskExecutor pendingRevisionExecutor;
    private final int batchSize;

    public PendingRevisionReconciliationScheduler(
            PendingDocumentRevisionRepository pendingDocumentRevisionRepository,
            PendingRevisionProcessor pendingRevisionProcessor,
            @Qualifier(PendingRevisionExecutorConfig.PENDING_REVISION_EXECUTOR_BEAN_NAME)
            ThreadPoolTaskExecutor pendingRevisionExecutor,
            @Value("${pending-revisions.reconcile-batch-size:100}") int batchSize) {
        this.pendingDocumentRevisionRepository = pendingDocumentRevisionRepository;
        this.pendingRevisionProcessor = pendingRevisionProcessor;
        this.pendingRevisionExecutor = pendingRevisionExecutor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${pending-revisions.reconcile-delay-ms:60000}")
    @SchedulerLock(name = "pendingRevisionReconciliation")
    public void reconcilePendingDocuments() {
        List<String> documentIds =
                pendingDocumentRevisionRepository.findDistinctDocumentIdsWithPendingEvents(
                        PageRequest.of(0, batchSize));

        if (documentIds.isEmpty()) {
            return;
        }

        log.debug("Submitting {} document(s) for pending revision reconciliation", documentIds.size());

        for (String documentId : documentIds) {
            pendingRevisionExecutor.execute(() -> pendingRevisionProcessor.processPendingForDocument(documentId));
        }
    }

}

package com.ryanwoolf.document_version_update_events.service;

import com.ryanwoolf.document_version_update_events.entity.Document;
import com.ryanwoolf.document_version_update_events.entity.PendingRevisionEvent;
import com.ryanwoolf.document_version_update_events.model.RevisionEvent;
import com.ryanwoolf.document_version_update_events.model.RevisionEventType;
import com.ryanwoolf.document_version_update_events.repository.DocumentRepository;
import com.ryanwoolf.document_version_update_events.repository.PendingDocumentRevisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class DocumentRevisionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRevisionService.class);

    private final DocumentRepository documentRepository;
    private final PendingDocumentRevisionRepository pendingDocumentRevisionRepository;

    public DocumentRevisionService(
            DocumentRepository documentRepository,
            PendingDocumentRevisionRepository pendingDocumentRevisionRepository) {
        this.documentRepository = documentRepository;
        this.pendingDocumentRevisionRepository = pendingDocumentRevisionRepository;
    }

    @Transactional
    public void processRevisionEvent(RevisionEvent revisionEvent) {
        String documentId = revisionEvent.documentId();
        lockDocumentAndPendingRows(documentId);

        Optional<Document> documentOptional = documentRepository.findById(documentId);
        long currentLatestSequence = documentOptional.map(Document::getLatestSequence).orElse(0L);

        if (shouldDiscardRevisionEvent(revisionEvent, documentOptional, currentLatestSequence)) {
            return;
        }

        if (tryApplyNextInSequence(revisionEvent, documentId, currentLatestSequence)) {
            return;
        }

        bufferPendingRevisionEvent(revisionEvent);
    }

    private boolean shouldDiscardRevisionEvent(
            RevisionEvent revisionEvent,
            Optional<Document> documentOptional,
            long currentLatestSequence) {
        if (isAlreadyAppliedDuplicate(revisionEvent, documentOptional)) {
            return true;
        }
        if (isStaleOrDuplicateSequence(revisionEvent, currentLatestSequence)) {
            return true;
        }
        if (isAlreadyBufferedAsPending(revisionEvent)) {
            return true;
        }
        return discardIncompatibleEventType(
                revisionEvent, documentOptional.isPresent(), currentLatestSequence);
    }

    private boolean isAlreadyAppliedDuplicate(
            RevisionEvent revisionEvent, Optional<Document> documentOptional) {
        if (documentOptional.isEmpty()) {
            return false;
        }
        if (!revisionEvent.eventId().equals(documentOptional.get().getLastEventId())) {
            return false;
        }
        log.debug(
                "Discarding duplicate revision event already applied eventId={} documentId={}",
                revisionEvent.eventId(),
                revisionEvent.documentId());
        return true;
    }

    private boolean isStaleOrDuplicateSequence(RevisionEvent revisionEvent, long currentLatestSequence) {
        if (revisionEvent.sequence() > currentLatestSequence) {
            return false;
        }
        log.debug(
                "Discarding stale or duplicate revision event eventId={} documentId={} sequence={} latestSequence={}",
                revisionEvent.eventId(),
                revisionEvent.documentId(),
                revisionEvent.sequence(),
                currentLatestSequence);
        return true;
    }

    private boolean isAlreadyBufferedAsPending(RevisionEvent revisionEvent) {
        if (!pendingDocumentRevisionRepository.existsByEventId(revisionEvent.eventId())) {
            return false;
        }
        log.debug(
                "Discarding revision event already buffered as pending eventId={} documentId={}",
                revisionEvent.eventId(),
                revisionEvent.documentId());
        return true;
    }

    private boolean tryApplyNextInSequence(
            RevisionEvent revisionEvent, String documentId, long currentLatestSequence) {
        if (revisionEvent.sequence() != currentLatestSequence + 1) {
            return false;
        }
        applyRevisionEvent(revisionEvent);
        drainConsecutivePendingEvents(documentId);
        return true;
    }

    @Transactional
    public void reconcilePendingForDocument(String documentId) {
        lockDocumentAndPendingRows(documentId);
        drainConsecutivePendingEvents(documentId);
    }

    /**
     * Enforces spec rules: CREATE establishes sequence 1 only; UPDATE cannot be the first revision.
     * CREATE cannot be applied as the next in-order revision once a document exists.
     */
    private boolean discardIncompatibleEventType(
            RevisionEvent revisionEvent, boolean documentExists, long currentLatestSequence) {
        RevisionEventType type = revisionEvent.eventType();
        if (type == RevisionEventType.CREATE) {
            if (revisionEvent.sequence() != 1L) {
                log.warn(
                        "Discarding CREATE with sequence other than 1 eventId={} documentId={} sequence={}",
                        revisionEvent.eventId(),
                        revisionEvent.documentId(),
                        revisionEvent.sequence());
                return true;
            }
        } else {
            if (!documentExists && revisionEvent.sequence() == 1L) {
                log.warn(
                        "Discarding UPDATE as first revision (use CREATE) eventId={} documentId={}",
                        revisionEvent.eventId(),
                        revisionEvent.documentId());
                return true;
            }
        }
        if (documentExists
                && revisionEvent.sequence() == currentLatestSequence + 1
                && type == RevisionEventType.CREATE) {
            log.warn(
                    "Discarding CREATE as next revision for existing document eventId={} documentId={} sequence={}",
                    revisionEvent.eventId(),
                    revisionEvent.documentId(),
                    revisionEvent.sequence());
            return true;
        }
        return false;
    }

    private void lockDocumentAndPendingRows(String documentId) {
        documentRepository.findByDocumentIdForUpdate(documentId);
        pendingDocumentRevisionRepository.findAllByDocumentIdForUpdate(documentId);
    }

    private void drainConsecutivePendingEvents(String documentId) {
        while (true) {
            long currentLatestSequence = documentRepository.findById(documentId)
                    .map(Document::getLatestSequence)
                    .orElse(0L);
            long nextSequence = currentLatestSequence + 1;

            Optional<PendingRevisionEvent> pendingRevisionEvent =
                    pendingDocumentRevisionRepository.findByDocumentIdAndSequence(documentId, nextSequence);
            if (pendingRevisionEvent.isEmpty()) {
                return;
            }

            Document document = documentRepository.findById(documentId).orElse(null);
            applyPendingRevisionEvent(document, pendingRevisionEvent.get());
            pendingDocumentRevisionRepository.delete(pendingRevisionEvent.get());
        }
    }

    private void applyRevisionEvent(RevisionEvent revisionEvent) {
        Document document = documentRepository.findById(revisionEvent.documentId()).orElse(null);
        applyRevision(
                document,
                revisionEvent.documentId(),
                revisionEvent.eventId(),
                revisionEvent.sequence(),
                toLocalDateTime(revisionEvent.timestamp()),
                revisionEvent.payload().title(),
                revisionEvent.payload().body());
    }

    private void applyPendingRevisionEvent(Document document, PendingRevisionEvent pendingRevisionEvent) {
        applyRevision(
                document,
                pendingRevisionEvent.getDocumentId(),
                pendingRevisionEvent.getEventId(),
                pendingRevisionEvent.getSequence(),
                pendingRevisionEvent.getEventTimestamp(),
                pendingRevisionEvent.getTitle(),
                pendingRevisionEvent.getBody());
    }

    private void applyRevision(
            Document document,
            String documentId,
            String eventId,
            long sequence,
            LocalDateTime updatedAt,
            String title,
            String body) {
        if (document == null) {
            document = new Document();
            document.setDocumentId(documentId);
        }

        document.setLastEventId(eventId);
        document.setLatestSequence(sequence);
        document.setTitle(title);
        document.setBody(body);
        document.setUpdatedAt(updatedAt);
        documentRepository.save(document);

        log.info(
                "Applied revision eventId={} documentId={} sequence={}",
                eventId,
                documentId,
                sequence);
    }

    private void bufferPendingRevisionEvent(RevisionEvent revisionEvent) {
        PendingRevisionEvent pendingRevisionEvent = new PendingRevisionEvent();
        pendingRevisionEvent.setEventId(revisionEvent.eventId());
        pendingRevisionEvent.setDocumentId(revisionEvent.documentId());
        pendingRevisionEvent.setSequence(revisionEvent.sequence());
        pendingRevisionEvent.setEventType(revisionEvent.eventType());
        pendingRevisionEvent.setEventTimestamp(toLocalDateTime(revisionEvent.timestamp()));
        pendingRevisionEvent.setTitle(revisionEvent.payload().title());
        pendingRevisionEvent.setBody(revisionEvent.payload().body());

        try {
            pendingDocumentRevisionRepository.save(pendingRevisionEvent);
        } catch (DataIntegrityViolationException dataIntegrityViolationException) {
            log.debug(
                    "Discarding duplicate pending revision eventId={} documentId={} sequence={}",
                    revisionEvent.eventId(),
                    revisionEvent.documentId(),
                    revisionEvent.sequence());
            return;
        }

        log.info(
                "Buffered out-of-order revision eventId={} documentId={} sequence={}",
                revisionEvent.eventId(),
                revisionEvent.documentId(),
                revisionEvent.sequence());
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

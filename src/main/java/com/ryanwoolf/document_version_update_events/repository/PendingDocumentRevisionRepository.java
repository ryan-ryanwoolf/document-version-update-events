package com.ryanwoolf.document_version_update_events.repository;

import com.ryanwoolf.document_version_update_events.entity.PendingRevisionEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PendingDocumentRevisionRepository extends JpaRepository<PendingRevisionEvent, String> {

    boolean existsByEventId(String eventId);

    Optional<PendingRevisionEvent> findByDocumentIdAndSequence(String documentId, long sequence);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pendingEvent FROM PendingRevisionEvent pendingEvent WHERE pendingEvent.documentId = :documentId")
    List<PendingRevisionEvent> findAllByDocumentIdForUpdate(@Param("documentId") String documentId);

    @Query(
            """
            SELECT DISTINCT pendingEvent.documentId
            FROM PendingRevisionEvent pendingEvent
            ORDER BY pendingEvent.documentId
            """)
    List<String> findDistinctDocumentIdsWithPendingEvents(Pageable pageable);

}

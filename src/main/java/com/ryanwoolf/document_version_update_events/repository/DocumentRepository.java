package com.ryanwoolf.document_version_update_events.repository;

import com.ryanwoolf.document_version_update_events.entity.Document;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT document FROM Document document WHERE document.documentId = :documentId")
    Optional<Document> findByDocumentIdForUpdate(@Param("documentId") String documentId);

    @Query(
            value = """
                    SELECT document_id AS documentId,
                           latest_sequence AS latestSequence,
                           title AS title,
                           body AS body,
                           updated_at AS updatedAt,
                           ts_rank(search_vector, plainto_tsquery('english', :query)) AS rank
                    FROM "lexis-nexis-events".document_state
                    WHERE search_vector @@ plainto_tsquery('english', :query)
                    ORDER BY rank DESC, updated_at DESC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<DocumentSearchProjection> searchByFullText(@Param("query") String query, @Param("limit") int limit);

}

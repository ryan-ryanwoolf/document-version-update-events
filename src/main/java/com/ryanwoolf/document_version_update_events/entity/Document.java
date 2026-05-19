package com.ryanwoolf.document_version_update_events.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@NoArgsConstructor
@Getter
@Setter
@Table(name = "document_state")
public class Document {

    @Id
    @Column(name = "document_id", nullable = false, length = 100)
    private String documentId;

    @Column(name = "last_event_id", nullable = false, length = 100, unique = true)
    private String lastEventId;

    @Column(name = "latest_sequence", nullable = false)
    private long latestSequence;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "search_vector", insertable = false, updatable = false, columnDefinition = "tsvector")
    private String searchVector;

}

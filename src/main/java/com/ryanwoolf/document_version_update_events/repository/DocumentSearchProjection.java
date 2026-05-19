package com.ryanwoolf.document_version_update_events.repository;

import java.time.LocalDateTime;

public interface DocumentSearchProjection {

    String getDocumentId();

    long getLatestSequence();

    String getTitle();

    String getBody();

    LocalDateTime getUpdatedAt();

    double getRank();

}

package com.ryanwoolf.document_version_update_events.dto;

import java.time.LocalDateTime;

public record DocumentStateResponse(
        String documentId,
        long latestSequence,
        String title,
        String body,
        LocalDateTime updatedAt) {
}

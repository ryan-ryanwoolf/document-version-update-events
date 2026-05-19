package com.ryanwoolf.document_version_update_events.dto;

import java.util.List;

public record DocumentSearchResponse(
        String query,
        int limit,
        int resultCount,
        List<DocumentSearchResultItem> results) {
}

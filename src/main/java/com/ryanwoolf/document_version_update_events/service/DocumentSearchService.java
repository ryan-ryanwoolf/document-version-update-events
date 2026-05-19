package com.ryanwoolf.document_version_update_events.service;

import com.ryanwoolf.document_version_update_events.dto.DocumentSearchResponse;
import com.ryanwoolf.document_version_update_events.dto.DocumentSearchResultItem;
import com.ryanwoolf.document_version_update_events.exception.InvalidSearchQueryException;
import com.ryanwoolf.document_version_update_events.repository.DocumentRepository;
import com.ryanwoolf.document_version_update_events.repository.DocumentSearchProjection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class DocumentSearchService {

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{C}");

    private final DocumentRepository documentRepository;
    private final int maxQueryLength;
    private final int defaultLimit;
    private final int maxLimit;

    public DocumentSearchService(
            DocumentRepository documentRepository,
            @Value("${app.search.max-query-length:200}") int maxQueryLength,
            @Value("${app.search.default-limit:20}") int defaultLimit,
            @Value("${app.search.max-limit:100}") int maxLimit) {
        this.documentRepository = documentRepository;
        this.maxQueryLength = maxQueryLength;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    @Transactional(readOnly = true)
    public DocumentSearchResponse search(String rawQuery, Integer requestedLimit) {
        String query = normalizeQuery(rawQuery);
        int limit = resolveLimit(requestedLimit);

        try {
            List<DocumentSearchProjection> matches = documentRepository.searchByFullText(query, limit);
            List<DocumentSearchResultItem> results = matches.stream()
                    .map(match -> new DocumentSearchResultItem(
                            match.getDocumentId(),
                            match.getLatestSequence(),
                            match.getTitle(),
                            match.getBody(),
                            match.getUpdatedAt(),
                            match.getRank()))
                    .toList();

            return new DocumentSearchResponse(query, limit, results.size(), results);
        } catch (DataAccessException dataAccessException) {
            throw new InvalidSearchQueryException("Search query could not be processed", dataAccessException);
        }
    }

    private String normalizeQuery(String rawQuery) {
        if (rawQuery == null) {
            throw new InvalidSearchQueryException("Search query is required");
        }

        String trimmedQuery = rawQuery.trim();
        if (trimmedQuery.isEmpty()) {
            throw new InvalidSearchQueryException("Search query must not be blank");
        }

        if (trimmedQuery.length() > maxQueryLength) {
            throw new InvalidSearchQueryException(
                    "Search query must be at most " + maxQueryLength + " characters");
        }

        if (CONTROL_CHARACTERS.matcher(trimmedQuery).find()) {
            throw new InvalidSearchQueryException("Search query contains invalid control characters");
        }

        return trimmedQuery;
    }

    private int resolveLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? defaultLimit : requestedLimit;
        if (limit < 1 || limit > maxLimit) {
            throw new InvalidSearchQueryException("Limit must be between 1 and " + maxLimit);
        }
        return limit;
    }

}

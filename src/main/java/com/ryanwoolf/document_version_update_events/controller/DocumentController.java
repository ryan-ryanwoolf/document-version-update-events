package com.ryanwoolf.document_version_update_events.controller;

import com.ryanwoolf.document_version_update_events.dto.DocumentSearchResponse;
import com.ryanwoolf.document_version_update_events.dto.DocumentStateResponse;
import com.ryanwoolf.document_version_update_events.service.DocumentQueryService;
import com.ryanwoolf.document_version_update_events.service.DocumentSearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/document")
@Validated
public class DocumentController {

    private final DocumentSearchService documentSearchService;
    private final DocumentQueryService documentQueryService;

    public DocumentController(
            DocumentSearchService documentSearchService,
            DocumentQueryService documentQueryService) {
        this.documentSearchService = documentSearchService;
        this.documentQueryService = documentQueryService;
    }

    @GetMapping("/search")
    public ResponseEntity<DocumentSearchResponse> search(
            @RequestParam("q")
            @NotBlank(message = "Search query (q) is required")
            @Size(max = 200, message = "Search query (q) must be at most 200 characters")
            String query,
            @RequestParam(value = "limit", required = false)
            @Min(value = 1, message = "Limit must be at least 1")
            @Max(value = 100, message = "Limit must be at most 100")
            Integer limit) {
        DocumentSearchResponse response = documentSearchService.search(query, limit);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentStateResponse> getById(
            @PathVariable("documentId")
            @NotBlank(message = "documentId is required")
            @Size(max = 100, message = "documentId must be at most 100 characters")
            String documentId) {
        return ResponseEntity.ok(documentQueryService.getByDocumentId(documentId));
    }
}

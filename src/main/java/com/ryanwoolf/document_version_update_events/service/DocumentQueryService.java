package com.ryanwoolf.document_version_update_events.service;

import com.ryanwoolf.document_version_update_events.dto.DocumentStateResponse;
import com.ryanwoolf.document_version_update_events.entity.Document;
import com.ryanwoolf.document_version_update_events.exception.DocumentNotFoundException;
import com.ryanwoolf.document_version_update_events.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentQueryService {

    private final DocumentRepository documentRepository;

    public DocumentQueryService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public DocumentStateResponse getByDocumentId(String documentId) {
        Document document = documentRepository.findById(documentId).orElseThrow(
                () -> new DocumentNotFoundException(documentId));
        return new DocumentStateResponse(
                document.getDocumentId(),
                document.getLatestSequence(),
                document.getTitle(),
                document.getBody(),
                document.getUpdatedAt());
    }
}

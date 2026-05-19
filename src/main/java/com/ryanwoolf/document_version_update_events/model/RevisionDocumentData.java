package com.ryanwoolf.document_version_update_events.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record RevisionDocumentData(
        @JsonProperty("title") @NotBlank String title,
        @JsonProperty("body") @NotBlank String body) {
}

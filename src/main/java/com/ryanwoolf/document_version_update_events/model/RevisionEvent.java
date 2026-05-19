package com.ryanwoolf.document_version_update_events.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.time.Instant;

/**
 * Assignment-compatible JSON: snake_case keys, ISO-8601 {@code timestamp} (e.g. {@code ...Z}),
 * {@code event_type} {@link RevisionEventType}.
 */
public record RevisionEvent(
        @JsonProperty("event_id") @NotBlank String eventId,
        @JsonProperty("document_id") @NotBlank String documentId,
        @Min(1) long sequence,
        @JsonProperty("event_type") @NotNull RevisionEventType eventType,
        @NotNull Instant timestamp,
        @JsonProperty("payload") @NotNull @Valid RevisionDocumentData payload) implements Serializable {

}

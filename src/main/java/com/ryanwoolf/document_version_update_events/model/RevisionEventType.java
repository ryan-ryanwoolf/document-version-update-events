package com.ryanwoolf.document_version_update_events.model;

/**
 * Spec assignment event kinds: CREATE establishes a document; UPDATE replaces content.
 */
public enum RevisionEventType {
    CREATE,
    UPDATE
}

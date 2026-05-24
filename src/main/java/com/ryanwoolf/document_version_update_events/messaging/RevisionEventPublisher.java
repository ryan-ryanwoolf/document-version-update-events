package com.ryanwoolf.document_version_update_events.messaging;

import com.ryanwoolf.document_version_update_events.model.RevisionEvent;

public interface RevisionEventPublisher {

    void publish(RevisionEvent revisionEvent);
}

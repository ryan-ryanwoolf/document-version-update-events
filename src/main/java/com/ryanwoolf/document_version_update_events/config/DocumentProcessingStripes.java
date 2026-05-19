package com.ryanwoolf.document_version_update_events.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@Component
public class DocumentProcessingStripes {

    private final ReentrantLock[] stripes;

    public DocumentProcessingStripes(@Value("${app.jms.document-stripes:64}") int stripeCount) {
        if (stripeCount < 1 || (stripeCount & (stripeCount - 1)) != 0) {
            throw new IllegalArgumentException("app.jms.document-stripes must be a positive power of two");
        }
        this.stripes = new ReentrantLock[stripeCount];
        for (int index = 0; index < stripeCount; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    public void withLock(String documentId, Runnable action) {
        ReentrantLock stripeLock = stripes[stripeIndex(documentId)];
        stripeLock.lock();
        try {
            action.run();
        } finally {
            stripeLock.unlock();
        }
    }

    private int stripeIndex(String documentId) {
        return Math.floorMod(documentId.hashCode(), stripes.length);
    }

}

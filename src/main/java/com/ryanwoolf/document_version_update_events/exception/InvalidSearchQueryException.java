package com.ryanwoolf.document_version_update_events.exception;

public class InvalidSearchQueryException extends RuntimeException {

    public InvalidSearchQueryException(String message) {
        super(message);
    }

    public InvalidSearchQueryException(String message, Throwable cause) {
        super(message, cause);
    }

}

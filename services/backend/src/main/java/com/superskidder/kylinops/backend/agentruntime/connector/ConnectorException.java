package com.superskidder.kylinops.backend.agentruntime.connector;

public class ConnectorException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public ConnectorException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}

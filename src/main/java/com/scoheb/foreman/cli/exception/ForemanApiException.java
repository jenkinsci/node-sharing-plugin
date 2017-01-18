package com.scoheb.foreman.cli.exception;

/**
 * Created by shebert on 18/01/17.
 */
public class ForemanApiException extends Exception {
    private String debugMessage;

    public ForemanApiException() {
        this.debugMessage = "";
    }

    public String getDebugMessage() {
        return debugMessage;
    }

    public void setDebugMessage(String debugMessage) {
        this.debugMessage = debugMessage;
    }

    public ForemanApiException(String message, String debugMessage) {
        super(message);
        this.debugMessage = debugMessage;
    }

    public ForemanApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public ForemanApiException(Throwable cause) {
        super(cause);
    }

    public ForemanApiException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}

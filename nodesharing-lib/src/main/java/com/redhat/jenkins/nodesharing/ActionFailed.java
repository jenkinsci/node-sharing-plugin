package com.redhat.jenkins.nodesharing;

/**
 * Action performed by the library has failed.
 *
 * Dedicated subclasses should be thrown.
 */
public abstract class ActionFailed extends RuntimeException {
    public ActionFailed(String message) {
        super(message);
    }

    public ActionFailed(String message, Throwable cause) {
        super(message, cause);
    }

    public ActionFailed(Throwable cause) {
        super(cause);
    }

    /**
     * Problem while talking to server.
     *
     * Network problem, service malfunction or failure performing an action.
     */
    public static class CommunicationError extends ActionFailed {
        public CommunicationError(String message) {
            super(message);
        }

        public CommunicationError(String message, Throwable cause) {
            super(message, cause);
        }

        public CommunicationError(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Library does not comprehend server reply.
     *
     * The response format and the library are not compatible.
     */
    public static class ProtocolMismatch extends ActionFailed {
        public ProtocolMismatch(String message) {
            super(message);
        }

        public ProtocolMismatch(String message, Throwable cause) {
            super(message, cause);
        }

        public ProtocolMismatch(Throwable cause) {
            super(cause);
        }
    }

}

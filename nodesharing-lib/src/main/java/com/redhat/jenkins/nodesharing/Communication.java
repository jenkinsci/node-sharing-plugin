package com.redhat.jenkins.nodesharing;

public class Communication {
    public static enum RunState {
        INVALID,
        FOUND,
        BLOCKED,
        STUCK,
        DONE,
        NOT_FOUND;

        final static RunState getStatus(final int status) {
            for (RunState r : RunState.values()) {
                if (r.ordinal() == status) {
                    return r;
                }
            }
            return RunState.INVALID;
        }
    };
}

package com.redhat.jenkins.nodesharing;

public class Communication {
    public static enum NodeState {
        INVALID,
        FOUND,
        NOT_FOUND;

        final static NodeState getStatus(final int status) {
            for (NodeState s : NodeState.values()) {
                if (s.ordinal() == status) {
                    return s;
                }
            }
            return NodeState.INVALID;
        }
    };

    public static enum RunState {
        INVALID,
        FOUND,
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

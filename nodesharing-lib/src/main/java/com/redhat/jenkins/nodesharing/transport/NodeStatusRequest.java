package com.redhat.jenkins.nodesharing.transport;

import javax.annotation.Nonnull;

public class NodeStatusRequest extends ExecutorEntity {
    private final @Nonnull String nodeName;

    /**
     * @param nodeName Name of the node to be returned.
     */
    public NodeStatusRequest(@Nonnull Fingerprint f, @Nonnull String nodeName) {
        super(f);
        this.nodeName = nodeName;
    }

    @Nonnull
    public String getNodeName() {
        return nodeName;
    }
}

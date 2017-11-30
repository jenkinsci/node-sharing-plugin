package com.redhat.jenkins.nodesharing.transport;

import javax.annotation.Nonnull;

public class NodeStatusRequest extends Entity {
    private final @Nonnull String nodeName;

    /**
     * @param nodeName Name of the node to be returned.
     */
    public NodeStatusRequest(
            @Nonnull String configRepoUrl,
            @Nonnull String version,
            @Nonnull String nodeName
    ) {
        super(configRepoUrl, version);
        this.nodeName = nodeName;
    }

    @Nonnull
    public String getNodeName() {
        return nodeName;
    }
}

/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharing.transport;

import javax.annotation.Nonnull;

/**
 * @author pjanouse.
 */
public class NodeStatusResponse extends ExecutorEntity {
    @Nonnull private String nodeName;
    @Nonnull private final Status status;

    public NodeStatusResponse(
            @Nonnull Fingerprint fingerprint,
            @Nonnull String nodeName,
            @Nonnull Status state
    ) {
        super(fingerprint);
        this.nodeName = nodeName;
        this.status = state;
    }

    /**
     * @return Node name.
     */
    @Nonnull
    public String getNodeName() {
        return nodeName;
    }

    /**
     * @return Node status.
     */
    @Nonnull
    public Status getStatus() {
        return status == null ? Status.INVALID : status;
    }

    /**
     *      'INVALID' if status can't be obtained
     *      'FOUND' if the node exists
     *      'IDLE' if the node is in the idle
     *      'CONNECTING' if the node is instancing (provisioning or connecting)
     *      'OFFLINE' if the node is in idle and offline
     *      'BUSY' if the node is executing
     *      'NOT_FOUND' if the node isn't exist
     */
    public static enum Status {
        INVALID,
        FOUND,
        IDLE,
        CONNECTING,
        OFFLINE,
        BUSY,
        NOT_FOUND;

        final static Status getState(final int status) {
            for (Status s : Status.values()) {
                if (s.ordinal() == status) {
                    return s;
                }
            }
            return Status.INVALID;
        }
    };
}

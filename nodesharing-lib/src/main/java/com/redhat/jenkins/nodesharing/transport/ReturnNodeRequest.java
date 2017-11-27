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
 * @author ogondza.
 */
public class ReturnNodeRequest extends ExecutorEntity {
    private final @Nonnull String nodeName;
    @Nonnull private final Status status;

    /**
     * @param nodeName Name of the node to be returned.
     * @param status
     *      'OK' if the host was used successfully,
     *      'FAILED' when executor failed to get the node onlin,
     *      other values are ignored.
     */
    public ReturnNodeRequest(@Nonnull Fingerprint f, @Nonnull String nodeName, @Nonnull Status status) {
        super(f);
        this.nodeName = nodeName;
        this.status = status;
    }

    public @Nonnull String getNodeName() {
        return nodeName;
    }

    public Status getStatus() {
        return status;
    }

    public enum Status {
        OK, FAILED;
    }
}

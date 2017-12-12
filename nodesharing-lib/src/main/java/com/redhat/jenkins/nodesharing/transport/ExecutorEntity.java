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
 * Entity sent from Executor to Orchestrator.
 *
 * Helper subclass to add initiator identity.
 *
 * @author ogondza.
 */
public class ExecutorEntity extends AbstractEntity {
    @Nonnull private final String executorName;

    public ExecutorEntity(@Nonnull Fingerprint fingerprint) {
        super(fingerprint.configRepoUrl, fingerprint.version);
        this.executorName = fingerprint.executorName;
    }

    public @Nonnull String getExecutorName() {
        return executorName;
    }

    /**
     * Mandatory fields specified to every {@link ExecutorEntity}.
     */
    public static final class Fingerprint {
        @Nonnull private final String configRepoUrl;
        @Nonnull private final String version;
        @Nonnull private final String executorName;

        public Fingerprint(@Nonnull String configRepoUrl, @Nonnull String version, @Nonnull String executorName) {

            this.configRepoUrl = configRepoUrl;
            this.version = version;
            this.executorName = executorName;
        }
    }
}

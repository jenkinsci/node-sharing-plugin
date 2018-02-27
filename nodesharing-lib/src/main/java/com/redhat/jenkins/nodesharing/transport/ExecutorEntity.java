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

    /**
     * Note that a) this is convenience mechanism rather than a security one as it is so easy to fake and b) we can not
     * use name as that has to be resolved against config repo which might not work (executor was removed, etc.).
     */
    private final @Nonnull String executorUrl;

    public ExecutorEntity(@Nonnull Fingerprint fingerprint) {
        super(fingerprint.configRepoUrl, fingerprint.version);
        this.executorUrl = fingerprint.executorUrl;
    }

    public @Nonnull String getExecutorUrl() {
        return executorUrl;
    }

    /**
     * Mandatory fields specified to every {@link ExecutorEntity}.
     */
    public static final class Fingerprint {
        private final @Nonnull String configRepoUrl;
        private final @Nonnull String version;
        private final @Nonnull String executorUrl;

        public Fingerprint(@Nonnull String configRepoUrl, @Nonnull String version, @Nonnull String executorUrl) {
            if (configRepoUrl == null) throw new IllegalArgumentException();
            if (version == null) throw new IllegalArgumentException();
            if (executorUrl == null) throw new IllegalArgumentException();
            this.configRepoUrl = configRepoUrl;
            this.version = version;
            this.executorUrl = executorUrl;
        }
    }
}

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
 * Abstract entity transmitted over wire.
 *
 * By convention, the implementations are immutable for the advantage of the receiver and valid / fully initialized after
 * constructed by sender to make sure necessary invariants are met before the entity is transmitted.
 *
 * Preferred way to serialize/deserialize object is to use automatic stream based methods which subclasses can override
 * if needed. The String based methods are for more convenience. The implementations are expected to preserve the existing
 * invariant that guarantees the serialized object will be "equivalent" to the source one after deserialized.
 *
 * @author ogondza.
 */
public abstract class AbstractEntity extends Entity {

    // Fields transferred with every request
    private final @Nonnull String configRepoUrl;
    private final @Nonnull String version;

    public AbstractEntity(@Nonnull String configRepoUrl, @Nonnull String version) {
        this.configRepoUrl = configRepoUrl;
        this.version = version;
    }

    @Nonnull
    public String getConfigRepoUrl() {
        return configRepoUrl;
    }

    @Nonnull
    public String getVersion() {
        return version;
    }
}

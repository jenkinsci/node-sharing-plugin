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

import com.redhat.jenkins.nodesharing.NodeDefinition;

import javax.annotation.Nonnull;

/**
 * @author ogondza.
 */
public class UtilizeNodeRequest extends AbstractEntity {

    private final @Nonnull String fileName;
    private final @Nonnull String definition;

    public UtilizeNodeRequest(@Nonnull String configRepoUrl, @Nonnull String version, @Nonnull NodeDefinition node) {
        super(configRepoUrl, version);
        fileName = node.getDeclaringFileName();
        definition = node.getDefinition();
    }

    public @Nonnull String getFileName() {
        return fileName;
    }

    public @Nonnull String getDefinition() {
        return definition;
    }
}

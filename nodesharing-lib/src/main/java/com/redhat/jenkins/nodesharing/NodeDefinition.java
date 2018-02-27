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
package com.redhat.jenkins.nodesharing;

import hudson.FilePath;
import hudson.model.labels.LabelAtom;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Definition of the node in Config Repository.
 *
 * This abstraction is ready to support various configuration formats.
 */
@Immutable
public abstract class NodeDefinition implements Serializable {
    private static final long serialVersionUID = -2736787874164916297L;

    private final @Nonnull String fileName;
    private final @Nonnull String definition;

    protected NodeDefinition(@Nonnull String fileName, @Nonnull String definition) {
        this.fileName = fileName;
        this.definition = definition;
    }

    /**
     * @return Name of the node.
     */
    public abstract String getName();

    /**
     * @return Labels of the node.
     */
    public abstract @Nonnull String getLabel();

    /**
     * Name of the file the node was declared in in config repo.
     * @see #create(String, String)
     */
    public @Nonnull String getDeclaringFileName() {
        return fileName;
    }

    /**
     * Textual definition of the node.
     * @see #create(String, String)
     */
    public @Nonnull String getDefinition() {
        return definition;
    }

    public @Nonnull Collection<LabelAtom> getLabelAtoms() {
        return LabelAtom.parse(getLabel());
    }

    public static @CheckForNull NodeDefinition create(@Nonnull FilePath file) throws IOException, InterruptedException {
        return create(file.getName(), file.readToString());
    }

    /**
     * Create definition from file name and the content.
     *
     * Following invariant must hold so we are able to recreate the node on the other side:
     * <tt>node.equals(NodeDefinition.create(node.getDeclaringFileName(), node.getDefinition()))</tt>
     */
    public static @CheckForNull NodeDefinition create(@Nonnull String declaringFileName, @Nonnull String definition) {
        if (declaringFileName.endsWith(".xml")) {
            return new Xml(declaringFileName, definition);
        }
        return null;
    }

    /**
     * XStream based node definition.
     */
    public static final class Xml extends NodeDefinition {
        private static final long serialVersionUID = 6932395574201798664L;

        private final @Nonnull String name;
        private final @Nonnull String label;

        public Xml(@Nonnull String fileName, @Nonnull String xml) {
            super(fileName, xml);
            this.name = fileName.replaceAll(".xml$", "");
            Matcher matcher = Pattern.compile("<label>(.*?)</label>").matcher(xml);
            if (!matcher.find()) {
                throw new IllegalStateException("No labels found in " + xml);
            }
            label = matcher.group(1).trim();
            if (label.isEmpty()) throw new IllegalArgumentException("No labels specified for node " + name);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public @Nonnull String getLabel() {
            return label;
        }
    }
}

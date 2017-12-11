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
import hudson.model.Label;
import hudson.model.labels.LabelAtom;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Definition of the node in Config Repository.
 *
 * This abstraction is ready to support various configuration formats.
 */
public abstract class NodeDefinition {

    /**
     * @return Name of the node.
     */
    public abstract String getName();

    /**
     * @return Labels of the node.
     */
    public abstract @Nonnull String getLabel();

    public @Nonnull Collection<LabelAtom> getLabelAtoms() {
        return LabelAtom.parse(getLabel());
    }

    public static @CheckForNull NodeDefinition create(@Nonnull FilePath file) throws IOException, InterruptedException {
        String name = file.getName();
        if (name.endsWith(".xml")) {
            return new Xml(file);
        }
        return null;
    }

    /**
     * XStream based node definition.
     */
    public static final class Xml extends NodeDefinition {
        private final @Nonnull String xml;
        private final @Nonnull String name;
        private final @Nonnull String label;

        public Xml(@Nonnull FilePath file) throws IOException, InterruptedException {
            this.xml = file.readToString();
            this.name = file.getBaseName().replaceAll(".xml$", "");
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

        public @Nonnull String getXml() {
            return xml;
        }

        @Override
        public @Nonnull String getLabel() {
            return label;
        }
    }
}

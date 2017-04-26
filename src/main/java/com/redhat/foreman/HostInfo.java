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
package com.redhat.foreman;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import hudson.Util;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Foreman host details. This class is immutable.
 *
 * @author ogondza.
 */
@JsonIgnoreProperties(ignoreUnknown=true)
public class HostInfo {

    private final String name;
    private final String labels;
    private final String remoteFs;
    private final @CheckForNull String javaPath; // Optional
    // null if free, identifier otherwise
    private final @CheckForNull String reserved;

    @JsonCreator
    public HostInfo(@JsonProperty("name") String name, @JsonProperty("parameters") List<Parameter> parameters) {
        this.name = Util.fixEmptyAndTrim(name);

        HashMap<String, String> params = new HashMap<String, String>(parameters.size());
        for (Parameter parameter : parameters) {
            params.put(parameter.name, parameter.value);
        }
        labels = Util.fixEmptyAndTrim(params.get(ForemanAPI.JENKINS_LABEL));
        remoteFs = Util.fixEmptyAndTrim(params.get(ForemanAPI.JENKINS_SLAVE_REMOTEFS_ROOT));
        javaPath = Util.fixEmptyAndTrim(params.get(ForemanAPI.JENKINS_SLAVE_JAVA_PATH));
        String r = Util.fixEmptyAndTrim(params.get(ForemanAPI.FOREMAN_SEARCH_RESERVEDPARAMNAME));
        reserved = "false".equals(r) ? null : r;
    }

    // Separate class to bind the nested json element.
    @JsonIgnoreProperties(ignoreUnknown=true)
    private static final class Parameter {
        private final String name;
        private final String value;

        @JsonCreator
        public Parameter(@JsonProperty("name") String name, @JsonProperty("value") String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * Name of the host. It also serves the purpose of hostname/IP address.
     *
     * @return Name of the host.
     */
    public String getName() {
        return name;
    }

    /**
     * String of label tokens.
     *
     * @return String of label tokens.
     */
    public String getLabels() {
        return labels;
    }

    public @Nonnull Set<LabelAtom> getLabelSet() {
        return Label.parse(labels);
    }

    /**
     * Determine whether this host matches label expression.
     *
     * This reverse the semantics of {@link Label#matches(Collection)} for convenience of provisioning clients as they
     * have to deal with the <tt>label == null</tt>.
     *
     * @param label Label for matching.
     * @return True if matches, False otherwise.
     */
    public boolean satisfies(@CheckForNull Label label) {
        return label != null && label.matches(getLabelSet());
    }

    /**
     * Path to remote Jenkins FS.
     *
     * @return Path to remote Jenkins FS.
     */
    public String getRemoteFs() {
        return remoteFs;
    }

    /**
     * Path to executable to be launched on node side to start the agent.
     *
     * @return Absolute path or null when default should be used.
     */
    public @CheckForNull String getJavaPath() {
        return javaPath;
    }

    /**
     * Identification of the current owner of the machine.
     *
     * @return True if it is reserved, False otherwise.
     */
    public @CheckForNull String getReservedFor() {
        return reserved;
    }

    public boolean isReserved() {
        return reserved != null;
    }
}

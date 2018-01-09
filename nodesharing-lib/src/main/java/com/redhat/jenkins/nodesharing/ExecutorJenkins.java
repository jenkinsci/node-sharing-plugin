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

import hudson.model.Failure;
import jenkins.model.Jenkins;
import org.apache.commons.codec.digest.DigestUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Objects;

/**
 * Remote Jenkins authorized to use this orchestrator.
 */
@Restricted(NoExternalUse.class)
public class ExecutorJenkins {

    private final @Nonnull URL url;
    private final @Nonnull String name;
    private final @Nonnull RestEndpoint rest;

    public ExecutorJenkins(@Nonnull String url, @Nonnull String name, String configRepoUrl) {
        try {
            Jenkins.checkGoodName(name);
            this.name = name;
        } catch (Failure ex) {
            throw new IllegalArgumentException(ex);
        }
        if (!url.endsWith("/")) {
            url += "/";
        }
        try {
            this.url = new URL(url);
            this.url.toURI();
        } catch (MalformedURLException|URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        rest = new RestEndpoint(url, "/cloud/" + inferCloudName(configRepoUrl) + "/api");
    }

    public static String inferCloudName(String url) {
        return DigestUtils.md5Hex(url);
    }

    public @Nonnull String getName() {
        return name;
    }

    public @Nonnull URL getUrl() {
        return url;
    }

    /**
     * Get URL to executors REST endpoint.
     *
     * @return REST endpoint URL.
     */
    public @Nonnull URL getEndpointUrl() {
        try {
            return new URL(url.toExternalForm());
        } catch (MalformedURLException e) {
            throw new Error(e); // base url was validated
        }
    }

    public @Nonnull RestEndpoint getRest() {
        return rest;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ExecutorJenkins that = (ExecutorJenkins) o;
        try {
            return Objects.equals(name, that.name) && Objects.equals(url.toURI(), that.url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public int hashCode() {
        try {
            return Objects.hash(url.toURI(), name);
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    @Override public String toString() {
        return name + ":" + url;
    }
}

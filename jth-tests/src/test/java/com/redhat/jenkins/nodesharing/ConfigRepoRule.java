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

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Create fake config repo from resources directory stub.
 */
public class ConfigRepoRule implements TestRule {

    public static final String ENDPOINT_PROPERTY_NAME = "com.redhat.jenkins.nodesharingbackend.Pool.ENDPOINT";

    private final List<File> repos = new ArrayList<>();

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                String oldEndpoint = System.getProperty(ENDPOINT_PROPERTY_NAME);
                try {
                    base.evaluate();
                } finally {
                    if (oldEndpoint == null) {
                        System.clearProperty(ENDPOINT_PROPERTY_NAME);
                    } else {
                        System.setProperty(ENDPOINT_PROPERTY_NAME, oldEndpoint);
                    }
                    for (File repo : repos) {
                        Util.deleteRecursive(repo);
                    }
                }
            }
        };
    }

    protected GitClient create(URL repoSources) throws Exception {
        File orig = new File(repoSources.toURI());
        assertTrue(orig.isDirectory());
        File repo = File.createTempFile("jenkins.nodesharing", getClass().getSimpleName());
        assert repo.delete();
        assert repo.mkdir();
        FileUtils.copyDirectory(orig, repo);
        repos.add(repo);

        StreamTaskListener listener = new StreamTaskListener(System.err, Charset.defaultCharset());
        GitClient git = Git.with(listener, new EnvVars()).in(repo).using("git").getClient();
        git.init();
        git.add("*");
        git.commit("Init");

        return git;
    }

    protected GitClient createReal(URL repoSources, Jenkins j) throws Exception {
        File orig = new File(repoSources.toURI());
        assertTrue(orig.isDirectory());
        File repo = File.createTempFile("jenkins.nodesharing.real", getClass().getSimpleName());
        assert repo.delete();
        assert repo.mkdir();
        FileUtils.copyDirectory(orig, repo);
        repos.add(repo);

        StreamTaskListener listener = new StreamTaskListener(System.err, Charset.defaultCharset());
        GitClient git = Git.with(listener, new EnvVars()).in(repo).using("git").getClient();
        git.init();
        git.add("*");
        git.commit("Init");

        FilePath config = git.getWorkTree().child("config");
        // TODO replase Jenkins URL
        String newConfig = config.readToString().replace("orchestrator.url=",
                "orchestrator.url=" + j.getRootUrl());
        config.write(newConfig, Charset.defaultCharset().name());
        git.add("config");

        FilePath jenkinses = git.getWorkTree().child("jenkinses");
        // TODO replase Jenkins URL
        String newJenkinses = jenkinses.readToString().replace("jenkins1=",
                "jenkins1=" + j.getRootUrl());
        jenkinses.write(newJenkinses, Charset.defaultCharset().name());
        git.add("jenkinses");

        // TODO create and store real Slave

        git.commit("Update");

        return git;
    }
}

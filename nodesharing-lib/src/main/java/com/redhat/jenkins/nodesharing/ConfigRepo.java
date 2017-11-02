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

import com.google.common.base.Joiner;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Descriptor;
import hudson.plugins.git.GitException;
import hudson.util.LogTaskListener;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read Configuration Repository for holding node sharing data.
 *
 * The class is not safe against several instances working with single <tt>workingDir</tt>, though it is safe to use it
 * from multiple threads.
 */
public class ConfigRepo {
    private static final Logger LOGGER = Logger.getLogger(ConfigRepo.class.getName());

    // Ensure content of repository is no manipulated while being read
    private final @Nonnull Object repoLock = new Object();

    private final @Nonnull String url;
    private final @Nonnull File workingDir;
    private final @Nonnull GitClient client;

    /**
     * Snapshot of the configuration at particular point in time.
     */
    public static final class Snapshot {

        private final @Nonnull HashMap<String, String> config;
        private final @Nonnull Set<ExecutorJenkins> jenkinses;
        private final @Nonnull Map<String, NodeDefinition> nodes;

        public Snapshot(@Nonnull HashMap<String, String> config, @Nonnull Set<ExecutorJenkins> jenkinses, @Nonnull Map<String, NodeDefinition> nodes) {
            this.config = config;
            this.jenkinses = jenkinses;
            this.nodes = nodes;
        }

        public @Nonnull Map<String, NodeDefinition> getNodes() {
            return nodes;
        }

        public @Nonnull HashMap<String, String> getConfig() {
            return config;
        }

        public @Nonnull Set<ExecutorJenkins> getJenkinses() {
            return jenkinses;
        }
    }

    /**
     * The state of config repo is considered illegal so it should not be used.
     */
    public static final class IllegalState extends RuntimeException {
        private List<String> problems = new ArrayList<String>();

        public IllegalState() {}

        public IllegalState(String message) {
            super(message);
        }

        public void add(@Nonnull String... problems) {
            this.problems.addAll(Arrays.asList(problems));
        }

        public void throwIfProblemsFound() throws IllegalState {
            if (!problems.isEmpty()) {
                throw new IllegalState(Joiner.on("\n").join(problems));
            }
        }
    }

    public ConfigRepo(@Nonnull String url, @Nonnull File workingDir) throws IOException, InterruptedException {
        this.url = url;
        this.workingDir = workingDir;
        this.client = Git.with(new LogTaskListener(LOGGER, Level.FINE), new EnvVars())
                .in(workingDir)
                .using("git")
                .getClient()
        ;
    }

    public @Nonnull ObjectId getHead() throws InterruptedException, GitException {
        return client.getHeadRev(url, "master");
    }

    public void update() throws InterruptedException, GitException {
        synchronized (repoLock) {
            client.clone_().url(url).execute();
            client.checkout().branch("master").ref("origin/master").execute();
        }
    }

    public Snapshot read() throws IOException, InterruptedException {
        synchronized (repoLock) {
            IllegalState problems = new IllegalState();

            HashMap<String, String> config = null;
            Set<ExecutorJenkins> jenkinses = null;
            Map<String, NodeDefinition> hosts = null;

            FilePath configFile = new FilePath(workingDir).child("config");
            if (!configFile.exists()) {
                problems.add("No file named 'config' found in Config Repository " + url);
            } else {
                config = getProperties(configFile);
                String orchestratorUrl = config.get("orchestrator.url");
                if (orchestratorUrl == null) {
                    problems.add("No orchestrator.url specified by Config Repository " + url);
                } else try { // Yep, an else-try statement
                    new URL(orchestratorUrl);
                } catch (MalformedURLException e) {
                    problems.add(orchestratorUrl + " is not valid url: " + e.getMessage());
                }
            }

            FilePath jenkinsesFile = new FilePath(workingDir).child("jenkinses");
            if (!jenkinsesFile.exists()) {
                problems.add("No file named 'jenkinses' found in Config Repository " + url);
            } else {
                jenkinses = getJenkinses(jenkinsesFile);
            }

            FilePath nodesDir = new FilePath(workingDir).child("nodes");
            if (!jenkinsesFile.exists()) {
                problems.add("No directory named 'nodes' found in Config Repository " + url);
            } else {
                hosts = readNodes(nodesDir);
            }

            problems.throwIfProblemsFound();

            return new Snapshot(config, jenkinses, hosts);
        }
    }

    private @Nonnull Set<ExecutorJenkins> getJenkinses(FilePath jenkinsesFile) throws IOException, InterruptedException {
        Properties config = new Properties();
        try (InputStream is = jenkinsesFile.read()) {
            config.load(is);
        }
        HashSet<ExecutorJenkins> jenkinses = new LinkedHashSet<>();
        for (Map.Entry<Object, Object> entry : config.entrySet()) {
            jenkinses.add(new ExecutorJenkins((String) entry.getValue(), (String) entry.getKey()));
        }
        return Collections.unmodifiableSet(jenkinses);
    }

    private @Nonnull HashMap<String, String> getProperties(FilePath configFile) throws IOException, InterruptedException {
        Properties config = new Properties();
        try (InputStream is = configFile.read()) {
            config.load(is);
        }

        // There is no easy way to make Properties unmodifiable or create a defensive copy. Also, the type of
        // Map<Object, Object> is not desirable here as well.
        HashMap<String, String> c = new HashMap<>();
        for (Map.Entry<Object, Object> entry : config.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String) {
                Object value = entry.getValue();
                if (value instanceof  String) {
                    c.put((String) key, (String) value);
                }
            }
        }
        return c;
    }

    private @Nonnull Map<String, NodeDefinition> readNodes(FilePath nodesDir) throws IOException, InterruptedException {
        Map<String, NodeDefinition> nodes = new HashMap<>();
        for (FilePath entry : nodesDir.list()) {
            if (entry.isDirectory()) throw new IllegalArgumentException("No directories expected in nodes dir");

            NodeDefinition nd = NodeDefinition.create(entry);
            if (nd == null) throw new IllegalArgumentException("Unknown node definition in " + entry.getBaseName());

            nodes.put(nd.getName(), nd);
        }
        return nodes;
    }
}

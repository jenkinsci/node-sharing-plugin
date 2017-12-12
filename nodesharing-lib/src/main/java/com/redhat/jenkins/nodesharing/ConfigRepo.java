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
import hudson.plugins.git.GitException;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
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
    private @CheckForNull GitClient client;

    @GuardedBy("repoLock")
    private @CheckForNull Snapshot snapshot;

    public ConfigRepo(@Nonnull String url, @Nonnull File workingDir) {
        this.url = url;
        this.workingDir = workingDir;
    }

    /**
     * Get snapshot or remote repo state or the last working.
     *
     * @return Latest snapshot or the most recent working one if latest can not be get.
     * @throws InterruptedException     When thread was interrupted while creating snapshot.
     * @throws IOException              When failed to create the log file for the operation.
     * @throws TaskLog.TaskFailed       When there ware problems reading the snapshot.
     */
    public @Nonnull Snapshot getSnapshot() throws InterruptedException, IOException, TaskLog.TaskFailed {
        Files.createDirectories(workingDir.toPath());
        TaskLog taskLog = new TaskLog(new File(workingDir.getAbsolutePath() + ".log"));
        try {
            ObjectId currentHead = getRemoteHead(taskLog);
            synchronized (repoLock) {
                if (snapshot != null && currentHead.equals(snapshot.source)) {
                    LOGGER.fine("No config update in " + url + " after: " + snapshot.source.name());
                } else {
                    taskLog.getLogger().printf("Node sharing config changes discovered %s%nPulling %s to %s%n", currentHead.name(), url, workingDir);
                    fetchChanges(taskLog);
                    ObjectId checkedOutHead = getClient(taskLog).revParse("HEAD");
                    assert currentHead.equals(checkedOutHead): "What was discovered was in fact checked out";
                    snapshot = readConfig(currentHead, taskLog);
                }
            }
        } catch (IOException|GitException ex) {
            taskLog.error(ex, "Unable to update config repo from %s", url);
        }

        taskLog.throwIfFailed("Unable to read snapshot from " + url);
        return snapshot;
    }

    private @Nonnull ObjectId getRemoteHead(@Nonnull TaskLog taskLog) throws InterruptedException, GitException {
        return getClient(taskLog).getHeadRev(url, "master");
    }

    private void fetchChanges(@Nonnull TaskLog taskLog) throws InterruptedException, GitException {
        synchronized (repoLock) {
            getClient(taskLog).clone_().url(url).execute();
            getClient(taskLog).checkout().branch("master").ref("origin/master").execute();
        }
    }

    private @Nonnull GitClient getClient(@Nonnull TaskLog taskLog) throws InterruptedException {
        if (client != null) return client;
        try {
            return client = Git.with(taskLog, new EnvVars())
                    .in(workingDir)
                    .using("git")
                    .getClient()
            ;
        } catch (IOException e) {
            throw new AssertionError("Creating local git client has failed", e);
        }
    }

    private @Nonnull Snapshot readConfig(
            @Nonnull ObjectId head, @Nonnull TaskLog taskLog
    ) throws IOException, InterruptedException, TaskLog.TaskFailed {
        synchronized (repoLock) {
            HashMap<String, String> config = null;
            Set<ExecutorJenkins> jenkinses = null;
            Map<String, NodeDefinition> hosts = null;

            String orchestratorUrl = null;
            FilePath configFile = new FilePath(workingDir).child("config");
            if (!configFile.exists()) {
                taskLog.error("No file named 'config' found in Config Repository");
            } else {
                config = getProperties(configFile);
                orchestratorUrl = config.get("orchestrator.url");
                if (orchestratorUrl == null) {
                    taskLog.error("No orchestrator.url specified by Config Repository");
                } else try { // Yep, an else-try statement
                    new URL(orchestratorUrl);
                } catch (MalformedURLException e) {
                    taskLog.error(e, "%s is not valid url", orchestratorUrl);
                }
            }

            FilePath jenkinsesFile = new FilePath(workingDir).child("jenkinses");
            if (!jenkinsesFile.exists()) {
                taskLog.error("No file named 'jenkinses' found in Config Repository");
            } else {
                jenkinses = getJenkinses(jenkinsesFile, url);
            }

            FilePath nodesDir = new FilePath(workingDir).child("nodes");
            if (!jenkinsesFile.exists()) {
                taskLog.error("No directory named 'nodes' found in Config Repository");
            } else {
                hosts = readNodes(nodesDir, taskLog);
            }

            taskLog.throwIfFailed("Unable to read config repository");
            return new Snapshot(head, config, jenkinses, hosts);
        }
    }

    private @Nonnull Set<ExecutorJenkins> getJenkinses(FilePath jenkinsesFile, String configRepoUrl) throws IOException, InterruptedException {
        Properties config = new Properties();
        try (InputStream is = jenkinsesFile.read()) {
            config.load(is);
        }
        HashSet<ExecutorJenkins> jenkinses = new LinkedHashSet<>();
        for (Map.Entry<Object, Object> entry : config.entrySet()) {
            jenkinses.add(new ExecutorJenkins((String) entry.getValue(), (String) entry.getKey(), configRepoUrl));
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

    private @Nonnull Map<String, NodeDefinition> readNodes(FilePath nodesDir, TaskLog taskLog) throws IOException, InterruptedException {
        Map<String, NodeDefinition> nodes = new HashMap<>();
        for (FilePath entry : nodesDir.list()) {
            if (entry.isDirectory()) {
                taskLog.println("No directories expected in nodes dir " + entry);
            }

            NodeDefinition nd = NodeDefinition.create(entry);
            if (nd == null) {
                taskLog.error("Unknown node definition in " + entry.getBaseName());
            } else {
                nodes.put(nd.getName(), nd);
            }
        }
        return nodes;
    }

    /**
     * Snapshot of the configuration at particular point in time.
     */
    public static final class Snapshot {

        private final static String ORCHESTRATOR_URL = "orchestrator.url";

        private final @Nonnull ObjectId source;
        private final @Nonnull HashMap<String, String> config;
        private final @Nonnull Set<ExecutorJenkins> jenkinses;
        private final @Nonnull Map<String, NodeDefinition> nodes;

        private Snapshot(
                @Nonnull ObjectId source,
                @Nonnull HashMap<String, String> config,
                @Nonnull Set<ExecutorJenkins> jenkinses,
                @Nonnull Map<String, NodeDefinition> nodes
        ) {
            this.source = source;
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

        public @Nonnull ExecutorJenkins getJenkins(@Nonnull String needle) throws NoSuchElementException {
            for (ExecutorJenkins jenkins : jenkinses) {
                if (jenkins.getUrl().toExternalForm().equals(needle)) {
                    return jenkins;
                }
            }

            throw new NoSuchElementException("No Jenkins executor configured for " + needle);
        }

        @CheckForNull
        public String getOrchestratorUrl() { return config.get(ORCHESTRATOR_URL); }
    }
}

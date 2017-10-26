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

import com.google.common.annotations.VisibleForTesting;
import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.util.LogTaskListener;
import jenkins.model.Jenkins;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author ogondza.
 */
@Restricted(NoExternalUse.class)
public class Pool {
    private static final Logger LOGGER = Logger.getLogger(Pool.class.getName());

    public static final String CONFIG_REPO_PROPERTY_NAME = Pool.class.getCanonicalName() + ".ENDPOINT";

    private static final Pool INSTANCE = new Pool();

    private final Object configLock = new Object();

    @GuardedBy("configLock")
    private Map<String, String> config;
    @GuardedBy("configLock")
    private Set<ExecutorJenkins> jenkinses;

    public static @Nonnull Pool getInstance() {
        return INSTANCE;
    }

    public static @CheckForNull String getConfigEndpoint() {
        String property = System.getProperty(CONFIG_REPO_PROPERTY_NAME);
        if (property == null) {
            LOGGER.severe("Node sharing pool not configured at " + CONFIG_REPO_PROPERTY_NAME);
        }
        return property;
    }

    private Pool() {}

    private void updateConfig(@Nonnull Map<String, String> config, @Nonnull Set<ExecutorJenkins> jenkinses) {
        synchronized (configLock) {
            this.config = config;
            this.jenkinses = jenkinses;
        }
    }

    @VisibleForTesting
    /*package*/ @CheckForNull Map<String, String> getConfig() {
        synchronized (configLock) {
            return config;
        }
    }

    public @CheckForNull Set<ExecutorJenkins> getJenkinses() {
        synchronized (configLock) {
            return jenkinses;
        }
    }

    @Extension
    public static final class Updater extends PeriodicWork {
        private static final File WORK_DIR = new File(Jenkins.getActiveInstance().getRootDir(), "node-sharing");
        private static final File CONFIG_DIR = new File(WORK_DIR, "config");

        private ObjectId oldHead;

        public static @Nonnull Updater getInstance() {
            ExtensionList<Updater> list = Jenkins.getInstance().getExtensionList(Updater.class);
            assert list.size() == 1;
            return list.iterator().next();
        }

        @Override
        public long getRecurrencePeriod() {
            return Functions.getIsUnitTest() ? Long.MAX_VALUE : MIN;
        }

        @Override
        protected void doRun() throws Exception {
            GitClient client = Git.with(new LogTaskListener(LOGGER, Level.FINE), new EnvVars())
                    .in(CONFIG_DIR).using("git").getClient()
            ;

            String configEndpoint = getConfigEndpoint();
            if (configEndpoint == null) return;

            ObjectId currentHead = client.getHeadRev(configEndpoint, "master");
            if (currentHead.equals(oldHead) && client.hasGitRepo()) {
                LOGGER.fine("No config update after: " + oldHead.name());
                return;
            }
            oldHead = currentHead;

            LOGGER.info("Nodesharing config changes discovered: " + oldHead.name());
            client.clone_().url(configEndpoint).execute();
            client.checkout().branch("master").ref("origin/master").execute();

            FilePath configFile = new FilePath(CONFIG_DIR).child("config");
            FilePath jenkinsesFile = new FilePath(CONFIG_DIR).child("jenkinses");
            Pool.getInstance().updateConfig(getProperties(configFile), getJenkinses(jenkinsesFile));

            FilePath nodesDir = new FilePath(CONFIG_DIR).child("nodes");
            updateNodes(readNodes(nodesDir));
        }

        private Set<ExecutorJenkins> getJenkinses(FilePath jenkinsesFile) throws IOException, InterruptedException {
            HashSet<ExecutorJenkins> executorJenkins = new LinkedHashSet<>();
            try(BufferedReader br = new BufferedReader(new InputStreamReader(jenkinsesFile.read()))) {
                for(String line; (line = br.readLine()) != null; ) {
                    executorJenkins.add(new ExecutorJenkins(line.trim()));
                }
            }
            return Collections.unmodifiableSet(executorJenkins);
        }

        private HashMap<String, String> getProperties(FilePath configFile) throws IOException, InterruptedException {
            Properties config = new Properties();
            try (InputStream is = configFile.read()) {
                config.load(is);
            }

            // There is no easy way to make Properties unmodifiable or create a defensive copy. Also, the type of
            // Map<Object, Object> is not desirable here as well.
            HashMap<String, String> c = new HashMap<>();
            for (Object key : config.keySet()) {
                if (key instanceof String) {
                    Object value = config.get(key);
                    if (value instanceof  String) {
                        c.put((String) key, (String) value);
                    }
                }
            }
            return c;
        }

        private Map<String, SharedNode> readNodes(FilePath nodesDir) throws IOException, InterruptedException, Descriptor.FormException {
            Map<String, SharedNode> nodes = new HashMap<>();
            for (FilePath xmlNode : nodesDir.list("*.xml")) {
                String xml = xmlNode.readToString();
                String hostName = xmlNode.getBaseName().replaceAll(".xml$", "");
                Matcher matcher = Pattern.compile("<label>(.*?)</label>").matcher(xml);
                if (!matcher.find()) {
                    throw new IllegalArgumentException("No labels found in " + xml);
                }
                String labels = matcher.group(1);
                nodes.put(hostName, new SharedNode(hostName, labels, xml));
            }
            return nodes;
        }

        private void updateNodes(Map<String, SharedNode> nodes) throws IOException, Descriptor.FormException {
            Jenkins j = Jenkins.getInstance();

            for (SharedNode node : nodes.values()) {
                SharedNode existing = (SharedNode) j.getNode(node.getNodeName());
                if (existing == null) {
                    // Add new ones
                    j.addNode(node);
                } else {
                    // Update existing
                    existing.updateBy(node);
                }
            }

            // Delete removed
            for (Node node : j.getNodes()) {
                if (node instanceof SharedNode && !nodes.containsKey(node.getNodeName())) {
                    ((SharedNode) node).deleteWhenIdle();
                }
            }
        }
    }
}

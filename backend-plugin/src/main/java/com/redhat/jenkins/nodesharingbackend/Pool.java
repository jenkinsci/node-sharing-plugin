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
package com.redhat.jenkins.nodesharingbackend;

import com.google.common.annotations.VisibleForTesting;
import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.NodeDefinition;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import hudson.model.AdministrativeMonitor;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.plugins.git.GitException;
import jenkins.model.Jenkins;
import org.eclipse.jgit.lib.ObjectId;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pool of shared hosts.
 *
 * Reflects the Config Repo.
 */
@Restricted(NoExternalUse.class)
@Extension
public class Pool extends AdministrativeMonitor {
    private static final Logger LOGGER = Logger.getLogger(Pool.class.getName());

    public static final String CONFIG_REPO_PROPERTY_NAME = Pool.class.getCanonicalName() + ".ENDPOINT";

    private final Object configLock = new Object();

    // TODO consider storing in Jenkins in case of crash with broken config in repo
    @GuardedBy("configLock")
    private @CheckForNull ConfigRepo.Snapshot config = null;

    @GuardedBy("configLock")
    private @CheckForNull ConfigError configError; // Null if no problem detected

    public static @Nonnull Pool getInstance() {
        ExtensionList<Pool> list = Jenkins.getInstance().getExtensionList(Pool.class);
        assert list.size() == 1;
        return list.iterator().next();
    }

    public @CheckForNull String getConfigEndpoint() {
        String property = System.getProperty(CONFIG_REPO_PROPERTY_NAME);
        if (property == null) {
            setError("Node sharing Config Repo not configured at " + CONFIG_REPO_PROPERTY_NAME);
        }
        return property;
    }

    public Pool() {}

    @Override
    public boolean isActivated() {
        return configError != null;
    }

    public @CheckForNull ConfigError getError() {
        synchronized (configLock) {
            return configError;
        }
    }

    private void setError(@Nullable String cause) {
        synchronized (configLock) {
            configError = new ConfigError(cause);
        }
    }

    @VisibleForTesting
    /*package*/ @CheckForNull ConfigRepo.Snapshot getConfig() {
        synchronized (configLock) {
            return config;
        }
    }

    private void updateConfig(@Nonnull ConfigRepo.Snapshot config) {
        synchronized (configLock) {
            this.config = config;
        }

        updateNodes(config.getNodes());
    }

    // TODO Queue.withLock?
    private void updateNodes(Map<String, NodeDefinition> nodes) {
        Jenkins j = Jenkins.getInstance();
        for (NodeDefinition nodeDefinition : nodes.values()) {
            SharedNode existing = (SharedNode) j.getNode(nodeDefinition.getName());
            if (existing == null) {
                // Add new ones
                try {
                    SharedNode node = SharedNode.get(nodeDefinition);
                    j.addNode(node);
                } catch (Exception ex) {
                    // Continue with other changes - this will be reattempted
                    LOGGER.log(Level.WARNING, "Unable to add node " + nodeDefinition.getName(), ex);
                }
            } else {
                // Update existing
                existing.updateBy(nodeDefinition);
            }
        }

        // Delete removed
        for (Node node : j.getNodes()) {
            if (node instanceof SharedNode && !nodes.containsKey(node.getNodeName())) {
                ((SharedNode) node).deleteWhenIdle();
            }
        }
    }

    public static final class ConfigError extends RuntimeException {
        public ConfigError(String message) {
            super(message);
        }
    }

    @Extension
    public static final class Updater extends PeriodicWork {
        private static final File WORK_DIR = new File(Jenkins.getActiveInstance().getRootDir(), "node-sharing");
        private static final File CONFIG_DIR = new File(WORK_DIR, "config");

        private ObjectId oldHead;

        public Updater() {
            // Configure UI logger for ease of maintenance
            LogRecorderManager log = Jenkins.getInstance().getLog();
            LogRecorder recorder = log.getLogRecorder("node-sharing");
            if (recorder == null) {
                recorder = new LogRecorder("node-sharing");
                recorder.targets.add(new LogRecorder.Target("com.redhat.jenkins.nodesharingbackend", Level.INFO));
                log.logRecorders.put("node-sharing", recorder);
            }
        }

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
            Pool pool = Pool.getInstance();
            String configEndpoint = pool.getConfigEndpoint();
            if (configEndpoint == null) return;

            ConfigRepo repo = new ConfigRepo(configEndpoint, CONFIG_DIR);

            try {
                ObjectId currentHead = repo.getHead();
                if (currentHead.equals(oldHead) && pool.getConfig() != null) {
                    LOGGER.fine("No config update after: " + oldHead.name());
                } else {
                    LOGGER.info("Nodesharing config changes discovered: " + currentHead.name());
                    repo.update();
                    ConfigRepo.Snapshot snapshot = repo.read();
                    pool.updateConfig(snapshot);
                    oldHead = currentHead;
                }
            } catch (GitException|ConfigRepo.IllegalState ex) {
                pool.setError(ex.getMessage());
                LOGGER.log(Level.WARNING, "Failed to update config repo", ex);
            }

            deletePendingNodes();
        }

        // Delayed deletion promised by NodeDefinition#deleteWhenIdle()
        private void deletePendingNodes() {
            for (Node node : Jenkins.getInstance().getNodes()) {
                if (node instanceof SharedNode && ((SharedNode) node).canBeDeleted()) {
                    ((SharedNode) node).deleteWhenIdle();
                }
            }
        }
    }
}

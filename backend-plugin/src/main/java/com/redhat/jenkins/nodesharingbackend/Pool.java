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
import com.redhat.jenkins.nodesharing.ConfigRepoAdminMonitor;
import com.redhat.jenkins.nodesharing.NodeDefinition;
import com.redhat.jenkins.nodesharing.TaskLog;
import hudson.AbortException;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
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
public class Pool {
    private static final Logger LOGGER = Logger.getLogger(Pool.class.getName());

    public static final String CONFIG_REPO_PROPERTY_NAME = Pool.class.getCanonicalName() + ".ENDPOINT";

    @Extension
    public static final ConfigRepoAdminMonitor ADMIN_MONITOR = new ConfigRepoAdminMonitor();
    private static final String MONITOR_CONTEXT = "Primary Config Repo";

    private final Object configLock = new Object();

    // TODO consider persisting in case of crash with broken config in repo
    @GuardedBy("configLock")
    private @CheckForNull ConfigRepo.Snapshot config = null;

    public static @Nonnull Pool getInstance() {
        ExtensionList<Pool> list = Jenkins.getInstance().getExtensionList(Pool.class);
        assert list.size() == 1;
        return list.iterator().next();
    }

    public @CheckForNull String getConfigEndpoint() {
        String property = System.getProperty(CONFIG_REPO_PROPERTY_NAME);
        if (property == null) {
            String msg = "Node sharing Config Repo not configured by '" + CONFIG_REPO_PROPERTY_NAME + "' property";
            ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(msg));
        }
        return property;
    }

    public Pool() {}

    @VisibleForTesting
    public @CheckForNull ConfigRepo.Snapshot getConfig() {
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
    private void updateNodes(final Map<String, NodeDefinition> nodes) {
        final Jenkins j = Jenkins.getActiveInstance();
        // Use queue lock so pool changes appear atomic from perspective of Queue#maintian and Api#doReportWorkload
        Queue.withLock(new Runnable() {
            @Override public void run() {
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
            }
        });

        // Delete removed
        for (Node node : j.getNodes()) {
            if (node instanceof SharedNode && !nodes.containsKey(node.getNodeName())) {
                ((SharedNode) node).deleteWhenIdle();
            }
        }
    }

    @Extension
    public static final class Updater extends PeriodicWork {
        private static final File WORK_DIR = new File(Jenkins.getActiveInstance().getRootDir(), "node-sharing");
        private static final File CONFIG_DIR = new File(WORK_DIR, "config");

        public static @Nonnull Updater getInstance() {
            ExtensionList<Updater> list = Jenkins.getInstance().getExtensionList(Updater.class);
            assert list.size() == 1;
            return list.iterator().next();
        }

        @Override
        public long getRecurrencePeriod() {
            return Functions.getIsUnitTest() ? Long.MAX_VALUE : MIN;
        }

        @Override @VisibleForTesting
        public void doRun() throws Exception {
            Pool pool = Pool.getInstance();
            String configEndpoint = pool.getConfigEndpoint();
            if (configEndpoint == null) return;

            ConfigRepo repo = new ConfigRepo(configEndpoint, CONFIG_DIR);

            Pool.ADMIN_MONITOR.clear();
            try {
                pool.updateConfig(repo.getSnapshot());
            } catch (IOException | TaskLog.TaskFailed ex) {
                Pool.ADMIN_MONITOR.report(MONITOR_CONTEXT, ex);
            }
        }
    }
}

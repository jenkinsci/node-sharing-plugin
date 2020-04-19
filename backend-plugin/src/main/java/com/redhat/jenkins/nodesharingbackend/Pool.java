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

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.annotations.VisibleForTesting;
import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.ConfigRepoAdminMonitor;
import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import com.redhat.jenkins.nodesharing.NodeDefinition;
import com.redhat.jenkins.nodesharing.TaskLog;
import hudson.AbortException;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.security.ACL;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pool of shared hosts.
 *
 * Reflects the Config Repo.
 */
@Restricted(NoExternalUse.class)
@Extension
@Symbol("nodeSharingPool")
public class Pool extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(Pool.class.getName());

    public static final String CONFIG_REPO_PROPERTY_NAME = "com.redhat.jenkins.nodesharingbackend.Pool.ENDPOINT";
    public static final String USERNAME_PROPERTY_NAME = "com.redhat.jenkins.nodesharingbackend.Pool.USERNAME";
    // TODO this will be visible in UI on /systemInfo (permission Jenkins/Administer)
    public static final String PASSWORD_PROPERTY_NAME = "com.redhat.jenkins.nodesharingbackend.Pool.PASSWORD";

    @Extension
    public static final ConfigRepoAdminMonitor ADMIN_MONITOR = new ConfigRepoAdminMonitor();
    private static final String MONITOR_CONTEXT = "Primary Config Repo";

    private final Object configLock = new Object();

    // TODO consider persisting in case of crash with broken config in repo
    @GuardedBy("configLock")
    private @CheckForNull ConfigRepo.Snapshot config = null;

    public static @Nonnull Pool getInstance() {
        ExtensionList<Pool> list = Jenkins.getInstance().getExtensionList(Pool.class);
        assert list.size() == 1; // $COVERAGE-IGNORE$
        return list.iterator().next();
    }

    public @Nonnull String getConfigRepoUrl() throws PoolMisconfigured {
        String property = Util.fixEmptyAndTrim(System.getProperty(CONFIG_REPO_PROPERTY_NAME));
        if (property == null) {
            String msg = "Node-sharing Config Repo not configured by '" + CONFIG_REPO_PROPERTY_NAME + "' property";
            ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(msg));
            throw new PoolMisconfigured(msg);
        }
        return property;
    }

    public @CheckForNull UsernamePasswordCredentials getExecutorCredential(ExecutorJenkins executor) {
        if(executor.getCredentialId() != null && !executor.getCredentialId().isEmpty()) {
            UsernamePasswordCredentials cred = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, Jenkins.getInstance(),
                            ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
                    CredentialsMatchers.withId(executor.getCredentialId())
            );

            LOGGER.info("credential id: " + executor.getCredentialId());
            if(cred == null) {
                ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(
                        "Credentials for node-sharing to " + executor.getName() + " not found in Jenkins."
                ));
                return null;
            }

            LOGGER.info("using credentials: " + cred);
            return cred;
        }

        String username = Util.fixEmptyAndTrim(System.getProperty(USERNAME_PROPERTY_NAME));
        if (username == null) {
            ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(
                    "No node-sharing username specified by " + USERNAME_PROPERTY_NAME + " property"
            ));
            return null;
        }
        String password = Util.fixEmptyAndTrim(System.getProperty(PASSWORD_PROPERTY_NAME));
        if (password == null) {
            ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(
                    "No node-sharing password specified by " + PASSWORD_PROPERTY_NAME + " property"
            ));
            return null;
        }

        return new UsernamePasswordCredentialsImpl(
                null, "transient-instance", "Node-sharing orchestrator credential", username, password
        );
    }

    public Pool() {}

    public @Nonnull ConfigRepo.Snapshot getConfig() throws PoolMisconfigured {
        synchronized (configLock) {
            if (config != null) return config;
            String configRepoUrl = getConfigRepoUrl(); // Rise more specific exception if the problem is missing config property
            throw new PoolMisconfigured("No config snapshot loaded from " + configRepoUrl);
        }
    }

    private void updateConfig(@Nonnull ConfigRepo.Snapshot config) {
        boolean needsReconfiguration = false;
        synchronized (configLock) {
            String oldRev = this.config == null ? null : this.config.getSource();
            String newRev = config.getSource();
            this.config = config;
            if (!newRev.equals(oldRev)) {
                LOGGER.info("Config repo updated from " + oldRev + " to " + newRev);
                needsReconfiguration = true;
            }
        }

        if (needsReconfiguration) {
            updateOrchestrator(config);

            // Start grid verification immediately after config repo change in a separate thread
            Computer.threadPoolForRemoting.submit(new Runnable() {
                @Override
                public void run() {
                    ReservationVerifier.getInstance().doRun();
                }
            });
        }
    }

    private void updateOrchestrator(final ConfigRepo.Snapshot config) {
        final Jenkins j = Jenkins.getInstance();
        // Use queue lock so pool changes appear atomic from perspective of Queue#maintian and Api#doReportWorkload
        Queue.withLock(new Runnable() {
            @Override public void run() {
                purgeOrphanedQueueItems(config.getJenkinses());
                updateNodes(config.getNodes());
            }

            private void purgeOrphanedQueueItems(Set<ExecutorJenkins> jenkinses) {
                Queue queue = j.getQueue();
                for (Queue.Item item : queue.getItems()) {
                    if (item.task instanceof ReservationTask) {
                        ReservationTask rt = (ReservationTask) item.task;
                        if (!jenkinses.contains(rt.getOwner())) {
                            // Queue reservations for executor no longer in config repo will be canceled
                            queue.cancel(rt);
                        }
                    }
                }
            }

            private void updateNodes(final Map<String, NodeDefinition> configured) {
                Map<String, ShareableNode> existing = ShareableNode.getAll();
                ArrayList<String> removed = new ArrayList<>(existing.keySet());
                removed.removeAll(configured.keySet());

                ArrayList<String> added = new ArrayList<>(configured.keySet());
                added.removeAll(existing.keySet());

                ArrayList<String> updated = new ArrayList<>(configured.keySet());
                updated.removeAll(removed);
                updated.removeAll(added);

                for (String remove : removed) {
                    ShareableNode n = existing.get(remove);
                    n.deleteWhenIdle();
                }

                for (String update : updated) {
                    existing.get(update).updateBy(configured.get(update));
                }

                for (String add : added) {
                    try {
                        ShareableNode node = new ShareableNode(configured.get(add));
                        j.addNode(node);
                    } catch (Exception ex) {
                        // Continue with other changes - this will be reattempted
                        LOGGER.log(Level.WARNING, "Unable to add node " + add, ex);
                    }
                }
            }
        });
    }

    /**
     * Make sure the orchestrator is in sync with the grid after startup that might be in the middle of grid operation.
     */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    @Restricted(DoNotUse.class)
    public static void ensureOrchestratorIsUpToDateWithTheGrid() throws Exception {
        LOGGER.info("Verifying state of the grid");
        Jenkins jenkins = Jenkins.getInstance();
        jenkins.doQuietDown(); // Prevent builds to be scheduled during the process
        jenkins.getQueue().clear(); // Clear any items that might be there from before restart - we can get more recent here
        try {
            Updater.getInstance().doRun();
        } catch (PoolMisconfigured ex) {
            // Do not treat the fatally. Show inactive orchestrator instead with problems reported.
            ex.printStackTrace();
        }
        ReservationVerifier.getInstance().doRun(); // Schedule all lost items
        jenkins.doCancelQuietDown();
        LOGGER.info(jenkins.getQueue().getItems().length + " reservations still in queue");
    }

    @Extension
    public static final class Updater extends PeriodicWork {
        private static final File WORK_DIR = new File(Jenkins.getInstance().getRootDir(), "node-sharing");
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
            String configEndpoint;
            try {
                configEndpoint = pool.getConfigRepoUrl();
            } catch (PoolMisconfigured ex) {
                return;
            }

            ConfigRepo repo = new ConfigRepo(configEndpoint, CONFIG_DIR);

            Pool.ADMIN_MONITOR.clear();
            try {
                pool.updateConfig(repo.getSnapshot());
            } catch (IOException | TaskLog.TaskFailed ex) {
                Pool.ADMIN_MONITOR.report(MONITOR_CONTEXT, ex);
            }
        }
    }

    public static final class PoolMisconfigured extends RuntimeException implements HttpResponse {
        private static final long serialVersionUID = -4744633341873004987L;

        private PoolMisconfigured(String message) {
            super(message);
        }

        @Override
        public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException {
            rsp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED, getMessage());
        }
    }

    // Helpers for JCasC
    @CheckForNull
    public String getConfigRepo() {
        return System.getProperty(CONFIG_REPO_PROPERTY_NAME);
    }

    @DataBoundSetter
    public void setConfigRepo(@Nonnull final String configRepo) {
        String prop = getConfigRepo();
        if (prop != null && !Objects.equals(prop, configRepo)) {
            // Clashes
            String msg = "Node-sharing Config Repo setting content clashes between environment ("
                    + prop + ") and JCasC (" + configRepo + ")";
            ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(msg));
            throw new PoolMisconfigured(msg);
        }
        System.setProperty(CONFIG_REPO_PROPERTY_NAME, configRepo);
    }

    @CheckForNull
    public String getUsername() {
        return System.getProperty(USERNAME_PROPERTY_NAME);
    }

    @DataBoundSetter
    public void setUsername(@Nonnull final String username) {
        String prop = getUsername();
        if (prop != null && !Objects.equals(prop, username)) {
            // Clashes
            String msg = "Node-sharing Username setting clashes between environment ("
                    + prop + ") and JCasC (" + username + ")";
            ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(msg));
            throw new PoolMisconfigured(msg);
        }
        System.setProperty(USERNAME_PROPERTY_NAME, username);
    }

    @CheckForNull
    public String getPassword() {
        return System.getProperty(PASSWORD_PROPERTY_NAME);
    }

    @DataBoundSetter
    public void setPassword(@Nonnull final String password) {
        String prop = getPassword();
        if (prop != null && !Objects.equals(prop, password)) {
            // Clashes
            String msg = "Node-sharing Password setting clashes between environment and JCasC";
            ADMIN_MONITOR.report(MONITOR_CONTEXT, new AbortException(msg));
            throw new PoolMisconfigured(msg);
        }
        System.setProperty(PASSWORD_PROPERTY_NAME, password);
    }
}

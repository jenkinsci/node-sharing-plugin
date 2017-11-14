package com.redhat.jenkins.nodesharingfrontend;

import com.google.common.annotations.VisibleForTesting;
import com.redhat.jenkins.nodesharing.ConfigRepo;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.PeriodicWork;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.OneShotEvent;
import hudson.util.Secret;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.anyOf;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.instanceOf;

import java.io.File;
import java.io.ObjectStreamException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.redhat.jenkins.nodesharingfrontend.launcher.SharedNodeComputerLauncherFactory;

/**
 * Foreman Shared Node Cloud implementation.
 */
public class SharedNodeCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(SharedNodeCloud.class.getName());

    private static final int SSH_DEFAULT_PORT = 22;

    /**
     * Git cloneable URL of config repository.
     */
    @Nonnull
    private String configRepoUrl;

    /**
     * The id of the credentials to use.
     */
    private String credentialsId;
    /**
     * The time in seconds to attempt to establish a SSH connection.
     */
    private Integer sshConnectionTimeOut;

    private transient Api api = null;

    private transient SharedNodeComputerLauncherFactory launcherFactory = null;

    /** All available hosts structured as an immutable map, indexed by their label atoms for performance reasons */
    @CopyOnWrite
    private transient volatile @Nonnull Map<String, HostInfo> hostsMap = Collections.emptyMap();

    private transient OneShotEvent startOperations = null;
    private transient Object startLock = null;

    private transient volatile ConfigRepo configRepo;
    private transient ConfigRepo.Snapshot latestConfig;

    private Object readResolve() throws ObjectStreamException {
        hostsMap = Collections.emptyMap();
        return this;
    }

    /**
     * Constructor for Config Page.
     *
     * @param configRepoUrl        ConfigRepo url
     * @param credentialsId        creds to use to connect to slave.
     * @param sshConnectionTimeOut timeout for SSH connection in secs.
     */
    @DataBoundConstructor
    public SharedNodeCloud(String configRepoUrl, String credentialsId, Integer sshConnectionTimeOut) {
        super(DigestUtils.md5Hex(configRepoUrl));

        this.configRepoUrl = configRepoUrl;
        this.configRepo = getConfigRepo();
        this.credentialsId = credentialsId;
        this.sshConnectionTimeOut = sshConnectionTimeOut;

        setOperational();
    }

    @Nonnull
    public final Api getApi() throws InterruptedException {
        if(api == null) {
            this.api = new Api(getLatestConfig().getOrchestratorUrl());
        }
        return api;
    }

    /**
     * Get Cloud name.
     *
     * @return name.
     */
    @Nonnull
    public String getName() {
        return name;
    }

    /**
     * Get Config repo url.
     *
     * @return configRepoUrl.
     */
    @Nonnull
    public String getConfigRepoUrl() {
        return configRepoUrl;
    }

    /**
     * Set Config repo url.
     *
     * @param configRepoUrl
     */
    @DataBoundSetter
    public void setConfigRepoUrl(@Nonnull final String configRepoUrl) {
        this.configRepoUrl = configRepoUrl;
    }

    /**
     * Get SSH connection time in seconds.
     *
     * @return timeout in secs.
     */
    @Nonnull
    public Integer getSshConnectionTimeOut() {
        return sshConnectionTimeOut;
    }

    /**
     * Set SSH connection time in seconds.
     *
     * @param sshConnectionTimeOut timeout in secs.
     */
    @DataBoundSetter
    public void setSshConnectionTimeOut(@Nonnull final Integer sshConnectionTimeOut) {
        this.sshConnectionTimeOut = sshConnectionTimeOut;
    }

    /**
     * Get credentials for SSH connection.
     *
     * @return credential id.
     */
    @Nonnull
    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * Setter for credentialsId.
     *
     * @param credentialsId to use to connect to slaves with.
     */
    @DataBoundSetter
    public void setCredentialsId(@Nonnull final String credentialsId) {
        this.credentialsId = credentialsId;
    }

    /**
     * Setter for Launcher Factory.
     *
     * @param launcherFactory launcherFactory to use.
     */
    @VisibleForTesting
    /*package for testing*/ public void setLauncherFactory(@Nonnull final SharedNodeComputerLauncherFactory launcherFactory) {
        this.launcherFactory = launcherFactory;
    }

    @Nonnull
    private ConfigRepo getConfigRepo() {
        synchronized (this) { // Prevent several ConfigRepo instances to be created over same directory
            if (configRepo != null) return configRepo;

            FilePath configRepoDir = Jenkins.getActiveInstance().getRootPath().child("node-sharing/configs/" + name);
            return configRepo = new ConfigRepo(configRepoUrl, new File(configRepoDir.getRemote()));
        }
    }

    /**
     * Get latest config repo snapshot.
     *
     * @return Snapshot or null when there are problem reading it.,
     */
    // TODO, are we OK throwing InterruptedException?
    @CheckForNull
    public ConfigRepo.Snapshot getLatestConfig() throws InterruptedException {
        if (latestConfig == null) {
            latestConfig = getConfigRepo().getSnapshot();
        }
        return latestConfig;
    }

    @Extension
    public static class ConfigRepoUpdater extends PeriodicWork {

        @Override public long getRecurrencePeriod() {
            return 5 * MIN;
        }

        @Override protected void doRun() throws Exception {
            for (Cloud c : Jenkins.getActiveInstance().clouds) {
                if (c instanceof SharedNodeCloud) {
                    SharedNodeCloud cloud = (SharedNodeCloud) c;
                    cloud.latestConfig = cloud.getConfigRepo().getSnapshot();

                    // TODO Check and fire cfg. was changed if necessary
                }
            }
        }
    }

    @Override
    public boolean canProvision(Label label) {
        long time = System.currentTimeMillis();
        LOGGER.finer("canProvision() asked for label '" + (label == null ? "" : label) + "'");

        for (Map.Entry<String, HostInfo> host: hostsMap.entrySet()) {

            try {
                if (host.getValue().satisfies(label)) {
                    LOGGER.info("canProvision returns True for label '" +
                            (label == null ? "" : label) + "' in "
                            + Util.getTimeSpanString(System.currentTimeMillis() - time));
                    return true;
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unexpected exception occurred in canProvision(): ", e);
                continue;
            }
        }
        LOGGER.info("canProvision returns False for label '" +
                (label == null ? "" : label) + "' in "
                + Util.getTimeSpanString(System.currentTimeMillis() - time));
        return false;
    }

    @Nonnull
    @Override
    public Collection<PlannedNode> provision(@CheckForNull final Label label, final int excessWorkload) {
        Collection<NodeProvisioner.PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();
        return result;
    }

    /**
     * Get the list of hosts available for reservation for the label.
     *
     * Since we are working with outdated data, client needs to check if hosts are free before allocating them.
     *
     * @param label Label to reserve for.
     * @return list of hosts that may be free for reservation.
     */
//    @Nonnull
//    private List<HostInfo> getHostsToReserve(@CheckForNull Label label) {
//        ArrayList<HostInfo> free = new ArrayList<HostInfo>();
//        ArrayList<HostInfo> used = new ArrayList<HostInfo>();
//        for (Map.Entry<String, HostInfo> h : hostsMap.entrySet()) {
//            if (h.getValue().satisfies(label)) {
//                HostInfo host = h.getValue();
//                if (host.isReserved()) {
//                    used.add(host);
//                } else {
//                    free.add(host);
//                }
//            }
//        }
//
//        // Get free hosts first, reserved last. We should not remove them altogether as they might not be reserved any longer.
//        free.addAll(used);
//        return free;
//    }

    /**
     * Get Cloud using provided name.
     *
     * @param name Cloud name.
     * @return a Sharing node Cloud.
     * @throws IllegalArgumentException if occurs.
     */
    @CheckForNull
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public static SharedNodeCloud getByName(final String name) throws IllegalArgumentException {
        if (name == null) {
            return null;
        }
        Jenkins instance = Jenkins.getInstance();
        if (instance.clouds != null) {
            Cloud cloud = instance.clouds.getByName(name);
            if (cloud == null) {
                return null;
            }
            if (cloud instanceof SharedNodeCloud) {
                return (SharedNodeCloud) cloud;
            }
        }
        throw new IllegalArgumentException(name + " is not a Foreman Shared Node cloud");
    }


    public static DisposableImpl addDisposableEvent(final String cloudName, final String hostName) {
        LOGGER.finer("Adding the host '" + hostName + "' to the disposable queue.");
        DisposableImpl disposable = new DisposableImpl(cloudName, hostName);
        AsyncResourceDisposer.get().dispose(disposable);
        return disposable;
    }

//    @Restricted(DoNotUse.class) // index.jelly
//    public Collection<HostInfo> getAllHosts() {
//        return hostsMap.values();
//    }

    private synchronized Object getStartLock() {
        if (startLock == null) {
            startLock = new Object();
        }
        return startLock;
    }

    /**
     * @return current Operational state.
     */
    public boolean isOperational() {
        synchronized (getStartLock()) {
            if (startOperations == null) {
                startOperations = new OneShotEvent();
            }
        }
        return startOperations.isSignaled();
    }

    /**
     * Set the SharedNodeCloud to Operational state (Operational == true).
     *
     * @return previous state.
     */
    public boolean setOperational() {
        return setOperational(true);
    }

    /**
     * Set the SharedNodeCloud to desired Operation state (Operation == status).
     *
     * @param status desired Operational state.
     * @return previous Operational state.
     */
    public boolean setOperational(final boolean status) {
        boolean oldStatus;
        synchronized (getStartLock()) {
            oldStatus = isOperational();
            if(!oldStatus && status) {
                startOperations.signal();
            }
        }
        return oldStatus;
    }

    /**
     * Descriptor for Cloud.
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Shared Node";
        }

        /**
         * Fill SSH credentials.
         *
         * @return list of creds.
         */
        public ListBoxModel doFillCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withMatching(anyOf(
                            instanceOf(SSHUserPrivateKey.class),
                            instanceOf(UsernamePasswordCredentials.class)),
                            CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class));
        }

        /**
         * Test connection.
         *
         * @param configRepoUrl Config repository URL.
         * @return Form Validation.
         * @throws ServletException if occurs.
         */
        public FormValidation doTestConnection(@Nonnull @QueryParameter("configRepoUrl") String configRepoUrl)
                throws ServletException {
            try {
                new URI(configRepoUrl);
            } catch (URISyntaxException e) {
                return FormValidation.error(Messages.InvalidURI(), e);
            }

            try {
                FilePath testConfigRepoDir = Jenkins.getActiveInstance().getRootPath().child("node-sharing/configs/testNewConfig");

                ConfigRepo testConfigRepo = new ConfigRepo(configRepoUrl, new File(testConfigRepoDir.getRemote()));
                ConfigRepo.Snapshot testSnapshot = testConfigRepo.getSnapshot();
                if(testSnapshot == null) {
                    return FormValidation.error(Messages.InvalidConfigRepo());
                }

                String version = new Api(testSnapshot.getOrchestratorUrl()).doDiscover();
                return FormValidation.okWithMarkup("<strong>" + Messages.TestConnectionOK(version) + "<strong>");
            // TODO: Unreachable, this checked exception can not bubble here. Who is supposed to throw this? This was obscured by delegating to method that declared to throw the supertype.
            //} catch (LoginException e) {
            //    return FormValidation.error(Messages.AuthFailure());
            } catch (Exception e) {
                e.printStackTrace();
                return FormValidation.error(e.getMessage(), e);
            }
        }
    }
}

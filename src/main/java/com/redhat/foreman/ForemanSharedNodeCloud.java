package com.redhat.foreman;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.Util;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.Cloud;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.OneShotEvent;
import hudson.util.Secret;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.anyOf;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.instanceOf;

import java.io.ObjectStreamException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.apache.commons.lang3.StringUtils;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.redhat.foreman.launcher.ForemanComputerLauncherFactory;
import com.redhat.foreman.launcher.ForemanSSHComputerLauncherFactory;

/**
 * Foreman Shared Node Cloud implementation.
 */
public class ForemanSharedNodeCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ForemanSharedNodeCloud.class.getName());

    private static final int SSH_DEFAULT_PORT = 22;

    /**
     * The cloudName
     */
    private String cloudName;
    /**
     * The Foreman url. Should include /api/v2
     */
    private String url;
    /**
     * User used to connect to Foreman
     */
    private String user;
    /**
     * Password used to connect to Foreman
     */
    private Secret password;
    /**
     * The id of the credentials to use.
     */
    private String credentialsId = null;
    /**
     * The time in seconds to attempt to establish a SSH connection.
     */
    private Integer sshConnectionTimeOut = null;

    private transient ForemanAPI api = null;
    private transient ForemanComputerLauncherFactory launcherFactory = null;

    /** All available hosts structured as an immutable map, indexed by their label atoms for performance reasons */
    @CopyOnWrite
    private transient volatile @Nonnull Map<Set<LabelAtom>, HostInfo> hostsMap = Collections.emptyMap();

    private transient OneShotEvent startOperations = null;
    private transient Object startLock = null;

    private Object readResolve() throws ObjectStreamException {
        hostsMap = Collections.emptyMap();
        return this;
    }

    /**
     * Constructor with name.
     *
     * @param name Name of cloud.
     */
    public ForemanSharedNodeCloud(String name) {
        super(name);
        this.cloudName = name;
    }

    /**
     * Constructor for Config Page.
     *
     * @param cloudName            name of cloud.
     * @param url                  Foreman URL.
     * @param user                 user to connect with.
     * @param password             password to connect with.
     * @param credentialsId        creds to use to connect to slave.
     * @param sshConnectionTimeOut timeout for SSH connection in secs.
     */
    @DataBoundConstructor
    public ForemanSharedNodeCloud(String cloudName, String url, String user, Secret password, String credentialsId,
                                  Integer sshConnectionTimeOut) {
        super(cloudName);

        this.cloudName = cloudName;
        this.url = url;
        this.user = user;
        this.password = password;
        this.credentialsId = credentialsId;
        this.sshConnectionTimeOut = sshConnectionTimeOut;
        api = new ForemanAPI(this.url, this.user, this.password);
        setOperational();
    }

    /**
     * Setter for credentialsId.
     *
     * @param credentialsId to use to connect to slaves with.
     */
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    /**
     * Setter for Launcher Factory.
     *
     * @param launcherFactory launcherFactory to use.
     */
    public void setLauncherFactory(ForemanComputerLauncherFactory launcherFactory) {
        this.launcherFactory = launcherFactory;
    }

    /**
     * Getter for Foreman API
     *
     * @return Foreman API.
     */
    ForemanAPI getForemanAPI() {
        if (api == null) {
            api = new ForemanAPI(this.url, this.user, this.password);
        }
        return api;
    }

    @Override
    public boolean canProvision(Label label) {
        LOGGER.finer("canProvision() asked for label '" + label + "'");
        long time = System.currentTimeMillis();

        for (Map.Entry<Set<LabelAtom>, HostInfo> host: hostsMap.entrySet()) {

            try {
                if (label.matches(host.getKey())) {
                    LOGGER.finer("canProvision returns True in "
                            + Util.getTimeSpanString(System.currentTimeMillis() - time));
                    return true;
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unexpected exception occurred in canProvision(): ", e);
                continue;
            }
        }
        LOGGER.finer("canProvision returns False in "
                + Util.getTimeSpanString(System.currentTimeMillis() - time));
        return false;
    }

    @Override
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public Collection<PlannedNode> provision(final @CheckForNull Label label, int excessWorkload) {
        Collection<NodeProvisioner.PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();
        if (excessWorkload > 0
                && !Jenkins.getInstance().isQuietingDown()
                && !Jenkins.getInstance().isTerminating()
                && canProvision(label)) {
            try {
                Future<Node> futurePlannedNode = Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    public Node call() throws Exception {
                        Node node = null;
                        try {
                            node = provision(label);
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Unhandled exception in provision(): ", e);
                            throw (AbortException) new AbortException().initCause(e);
                        }
                        if (node == null) {
                            throw new AbortException("No Foreman resources available");
                        }
                        return node;
                    }
                });
                String name = "ForemanNode";
                if (label != null) {
                    name = Util.fixNull(Util.fixEmptyAndTrim(label.toString()));
                }
                result.add(new NodeProvisioner.PlannedNode(
                        name,
                        futurePlannedNode,
                        1));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unhandled exception in provision(): ", e);
            }
        }
        return result;
    }

    /**
     * Perform the provisioning. This uses the Foreman hosts_reserve plugin to "lock"
     * a host for this requesting master.
     *
     * @param label linked Jenkins Label.
     * @return a Foreman Slave.
     * @throws Exception if occurs.
     */
    @CheckForNull
    private ForemanSharedNode provision(@CheckForNull Label label) throws Exception {
        LOGGER.finer("Trying to provision Foreman Shared Node for '" + label + "'");
        ForemanAPI foreman = getForemanAPI();
        try {
            for (HostInfo hi : getHostsToReserve(label)) {

                final HostInfo host = foreman.reserveHost(hi);
                if (host != null) {
                    try {
                        String labelsForHost = hi.getLabels();
                        String remoteFS = hi.getRemoteFs();

                        if (launcherFactory == null) {
                            launcherFactory = new ForemanSSHComputerLauncherFactory(hi.getName(), SSH_DEFAULT_PORT, credentialsId, sshConnectionTimeOut);
                        } else {
                            if (launcherFactory instanceof ForemanSSHComputerLauncherFactory) {
                                ((ForemanSSHComputerLauncherFactory) launcherFactory).configure(hi.getName(),
                                        SSH_DEFAULT_PORT, credentialsId, sshConnectionTimeOut);
                            }
                        }

                        RetentionStrategy<AbstractCloudComputer> strategy = new CloudRetentionStrategy(1);

                        List<? extends NodeProperty<?>> properties = Collections.emptyList();

                        LOGGER.finer("Returning a ForemanSharedNode for " + host);
                        return new ForemanSharedNode(this.cloudName,
                                hi.getName(),
                                hi.getName(),
                                labelsForHost,
                                remoteFS,
                                launcherFactory.getForemanComputerLauncher(),
                                strategy,
                                properties);
                    } catch (Error e) {
                        throw e;
                    } catch (Throwable e) {
                        addDisposableEvent(cloudName, hi.getName());
                    }
                }
            }
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Exception encountered when trying to create shared node. ", e);
        }

        // Something has changed and there are now no resources available...
        LOGGER.finer("No Foreman resources available...");
        return null;
    }

    /**
     * Get the list of hosts available for reservation for the label.
     *
     * Since we are working with outdated data, client needs to check if hosts are free before allocating them.
     *
     * @param label Label to reserve for.
     * @return list of hosts that may be free for reservation.
     */
    @Nonnull
    private List<HostInfo> getHostsToReserve(@CheckForNull Label label) {
        ArrayList<HostInfo> free = new ArrayList<HostInfo>();
        ArrayList<HostInfo> used = new ArrayList<HostInfo>();
        for (Map.Entry<Set<LabelAtom>, HostInfo> h : hostsMap.entrySet()) {
            if (h.getValue().satisfies(label)) {
                HostInfo host = h.getValue();
                if (host.isReserved()) {
                    used.add(host);
                } else {
                    free.add(host);
                }
            }
        }

        // Get free hosts first, reserved last. We should not remove them altogether as they might not be reserved any longer.
        free.addAll(used);
        return free;
    }

    /**
     * Get Cloud using provided name.
     *
     * @param name Cloud name.
     * @return a Foreman Cloud.
     * @throws IllegalArgumentException if occurs.
     */
    @CheckForNull
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public static ForemanSharedNodeCloud getByName(String name) throws IllegalArgumentException {
        if (name == null) {
            return null;
        }
        Jenkins instance = Jenkins.getInstance();
        if (instance.clouds != null) {
            Cloud cloud = instance.clouds.getByName(name);
            if (cloud == null) {
                return null;
            }
            if (cloud instanceof ForemanSharedNodeCloud) {
                return (ForemanSharedNodeCloud) cloud;
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

    /**
     * Get Foreman Cloud Name.
     *
     * @return name.
     */
    public String getCloudName() {
        return cloudName;
    }

    /**
     * Set Foreman Cloud Name.
     *
     * @param cloudName name of cloud.
     */
    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    /**
     * Get Foreman url.
     *
     * @return url.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Set Foreman url.
     *
     * @param url url.
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Get Foreman user.
     *
     * @return user.
     */
    public String getUser() {
        return user;
    }

    /**
     * Set Foreman user.
     *
     * @param user user.
     */
    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Foreman password.
     *
     * @return password as Secret.
     */
    public Secret getPassword() {
        return password;
    }

    /**
     * Set Foreman password.
     *
     * @param password Secret.
     */
    public void setPassword(Secret password) {
        this.password = password;
    }

    /**
     * Get credentials for SSH connection.
     *
     * @return credential id.
     */
    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * Get SSH connection time in seconds.
     *
     * @return timeout in secs.
     */
    public Integer getSshConnectionTimeOut() {
        return sshConnectionTimeOut;
    }

    /**
     * Update hosts data
     */
    void updateHostData() {
        while (!startOperations.isSignaled()) {
            // We are blocked
            LOGGER.warning("Update hosts data is still blocked, sleeping 10s.");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                LOGGER.severe("Sleeping interrupted! Returning...");
                return;
            }
        }

        try {

            Map<String, HostInfo> hosts = getForemanAPI().getCompatibleHosts();
            // Randomize nodes ordering
            List<HostInfo> list = new ArrayList<HostInfo>(hosts.values());
            Collections.shuffle(list);
            LinkedHashMap<Set<LabelAtom>, HostInfo> shuffleMap = new LinkedHashMap<Set<LabelAtom>, HostInfo>();
            for (HostInfo k : list) {
                shuffleMap.put(Label.parse(k.getLabels()), k);
            }

            hostsMap = Collections.unmodifiableMap(shuffleMap);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected exception occurred in updateHostData: ", e);
            hostsMap = Collections.emptyMap(); // Erase if we can not get the data
        }
    }

    @Restricted(DoNotUse.class) // index.jelly
    public Collection<HostInfo> getAllHosts() {
        return hostsMap.values();
    }

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
     * Set the ForemanSharedNodeCloud to Operational state (Operational == true).
     *
     * @return previous state.
     */
    public boolean setOperational() {
        return setOperational(true);
    }

    /**
     * Set the ForemanSharedNodeCloud to desired Operation state (Operation == status).
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
     * Descriptor for Foreman Cloud.
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Foreman Shared Node";
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
         * @param url      url.
         * @param user     user.
         * @param password password.
         * @return Foram Validation.
         * @throws ServletException if occurs.
         */
        public FormValidation doTestConnection(@QueryParameter("url") String url,
                                               @QueryParameter("user") String user,
                                               @QueryParameter("password") Secret password) throws ServletException {
            url = StringUtils.strip(StringUtils.stripToNull(url), "/");
            try {
                new URI(url);
            } catch (URISyntaxException e) {
                return FormValidation.error(Messages.InvalidURI(), e);
            }

            try {
                String url1 = url;
                url1 = StringUtils.strip(StringUtils.stripToNull(url1), "/");
                String version = new ForemanAPI(url1, user, password).getVersion();
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

    /**
     * Update the inventory periodically.
     */
    @Extension
    public static class ForemanSharedNodeWorker extends AsyncPeriodicWork {
        private final Logger LOGGER =
                Logger.getLogger(ForemanSharedNodeWorker.class.getName());

        public ForemanSharedNodeWorker() {
            super("ForemanSharedNodeWorker.Updater");
        }

        @Override
        @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
        public void execute(TaskListener listener) {
            Jenkins instance = Jenkins.getInstance();
            if (instance.clouds != null) {
                for (Cloud cloud : instance.clouds) {
                    if (cloud instanceof ForemanSharedNodeCloud) {
                        ForemanSharedNodeCloud foremanCloud = (ForemanSharedNodeCloud) cloud;
                        if(!foremanCloud.isOperational() ) {
                            // We are still blocked in updating data
                            LOGGER.warning("Updating data in ForemanSharedNodeWorker.Updater is still blocked for foreman cloud '"
                                    + foremanCloud.getCloudName() + "'!");
                            continue;
                        }

                        LOGGER.finer("Updating data for ForemanSharedNodeCloud '" + foremanCloud.getCloudName() + "'");
                        long time = System.currentTimeMillis();
                        foremanCloud.updateHostData();
                        LOGGER.finer("[COMPLETED] Updating data for ForemanSharedNodeCloud '" + foremanCloud.getCloudName()
                                + "' in " + Util.getTimeSpanString(System.currentTimeMillis() - time));
                    }
                }
            }
        }

        @Override
        public long getRecurrencePeriod() {
            return MIN;
        }

        @Override
        public String toString() {
            return "ForemanSharedNodeWorker.Updater";
        }
    }

}

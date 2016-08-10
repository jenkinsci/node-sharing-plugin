package com.redhat.foreman;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.Cloud;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.anyOf;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.instanceOf;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.security.auth.login.LoginException;
import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.redhat.foreman.launcher.ForemanComputerLauncherFactory;
import com.redhat.foreman.launcher.ForemanSSHComputerLauncherFactory;

/**
 * Foreman Shared Node Cloud implementation.
 *
 */
public class ForemanSharedNodeCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ForemanSharedNodeCloud.class);

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

    /**
     * Constructor with name.
     * @param name Name of cloud.
     */
    public ForemanSharedNodeCloud(String name) {
        super(name);
        this.cloudName = name;
    }

    /**
     * Constructor for Config Page.
     * @param cloudName name of cloud.
     * @param url Foreman URL.
     * @param user user to connect with.
     * @param password password to connect with.
     * @param credentialsId creds to use to connect to slave.
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
    }

    /**
     * Setter for credentialsId.
     * @param credentialsId to use to connect to slaves with.
     */
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    /**
     * Setter for Launcher Factory.
     * @param launcherFactory launcherFactory to use.
     */
    public void setLauncherFactory(ForemanComputerLauncherFactory launcherFactory) {
        this.launcherFactory = launcherFactory;
    }

    /**
     * Getter for Foreman API
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
        Map<String, String> hostsMap = getForemanAPI().getCompatibleHosts();
        Set<String> hosts = hostsMap.keySet();
        for (String host: hosts) {
            boolean match = label.matches(Label.parse(hostsMap.get(host)));
            if (match) {
                    return true;
            }
        }
        return false;
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        Collection<NodeProvisioner.PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();
        if (canProvision(label)) {
            try {
                result.add(new NodeProvisioner.PlannedNode(
                        label.toString(),
                        Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                            public Node call() throws Exception {
                                try {
                                    return provision(label);
                                } catch (Exception e) {
                                    LOGGER.error(e);
                                    throw e;
                                }
                            }
                        }),
                        1));
            } catch (Exception e) {
                LOGGER.error(e);
            }
        }
        return result;
    }

    /**
     * Perform the provisioning. This uses the Foreman hosts_reserve plugin to "lock"
     * a host for this requesting master.
     * @param label linked Jenkins Label.
     * @return a Foreman Slave.
     * @throws Exception if occurs.
     */
    private ForemanSharedNode provision(Label label) throws Exception {
        LOGGER.info("Trying to provision Foreman Shared Node for '" + label.toString() + "'");

        String reservedHostName = getHostToReserve(label);
        if (reservedHostName == null) {
            throw new Exception("No Foreman resources available...");
        }

        final JsonNode host = getForemanAPI().reserveHost(reservedHostName);
        if (host != null) {
            try {

                String certName = null;
                if (host.elements().hasNext()) {
                    JsonNode h = host.elements().next();
                    if (h.has("host")) {
                        certName = h.get("host").get("certname").asText();
                    } else {
                        if (h.has("certname")) {
                            certName = h.get("certname").asText();
                        } else {
                            throw new Exception("Reserve plugin did not return correct data?");
                        }
                    }
                }

                if (!reservedHostName.equals(certName)) {
                    throw new Exception("Reserved host is not what we asked to reserve?");
                }

                String labelsForHost = getForemanAPI().getLabelsForHost(reservedHostName);
                String remoteFS = getForemanAPI().getRemoteFSForSlave(reservedHostName);
                String hostIP = getForemanAPI().getIPForHost(reservedHostName);
                String hostForConnection = reservedHostName;
                if (hostIP != null) {
                    hostForConnection = hostIP;
                }

                if (launcherFactory == null) {
                    launcherFactory = new ForemanSSHComputerLauncherFactory(hostForConnection,
                            SSH_DEFAULT_PORT, credentialsId, sshConnectionTimeOut);
                } else {
                    if (launcherFactory instanceof ForemanSSHComputerLauncherFactory) {
                        ((ForemanSSHComputerLauncherFactory)launcherFactory).configure(hostForConnection,
                                SSH_DEFAULT_PORT, credentialsId, sshConnectionTimeOut);
                    }
                }

                RetentionStrategy<AbstractCloudComputer> strategy = new CloudRetentionStrategy(1);

                List<? extends NodeProperty<?>> properties = Collections.emptyList();

                LOGGER.info("Returning a ForemanSharedNode for " + hostForConnection);
                return new ForemanSharedNode(this.cloudName,
                        reservedHostName,
                        hostForConnection,
                        labelsForHost,
                        remoteFS,
                        launcherFactory.getForemanComputerLauncher(),
                        strategy,
                        properties);

            } catch (Exception e) {
                LOGGER.warn("Exception encountered when trying to create shared node. "
                        + "Trying to release Foreman resource '" + name + "'");
                throw e;
            }
        }

        // Something has changed and there are now no resources available...
        throw new Exception("No Foreman resources available...");
    }

    /**
     * Get host to Reserve for the label. Host must be free.
     * @param label Label to reserve for.
     * @return name of host that was reserved.
     */
    private String getHostToReserve(Label label) {
        Map<String, String> hostsMap = getForemanAPI().getCompatibleHosts();
        Set<String> hosts = hostsMap.keySet();
        for (String host: hosts) {
            boolean match = label.matches(Label.parse(hostsMap.get(host)));
            if (match) {
                if (getForemanAPI().isHostFree(host)) {
                    return host;
                }
            }
        }
        return null;
    }

    /**
     * Get Cloud using provided name.
     * @param name Cloud name.
     * @return a Foreman Cloud.
     * @throws IllegalArgumentException if occurs.
     */
    public static ForemanSharedNodeCloud getByName(String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getInstance().clouds.getByName(name);
        if (cloud instanceof ForemanSharedNodeCloud) {
            return (ForemanSharedNodeCloud)cloud;
        }
        throw new IllegalArgumentException(name + " is not an Foreman Shared Node cloud: " + cloud);
    }

    /**
     * Get Foreman Cloud Name.
     * @return name.
     */
    public String getCloudName() {
        return cloudName;
    }

    /**
     * Set Foreman Cloud Name.
     * @param cloudName name of cloud.
     */
    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    /**
     * Get Foreman url.
     * @return url.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Set Foreman url.
     * @param url url.
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Get Foreman user.
     * @return user.
     */
    public String getUser() {
        return user;
    }

    /**
     * Set Foreman user.
     * @param user user.
     */
    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Foreman password.
     * @return password as Secret.
     */
    public Secret getPassword() {
        return password;
    }

    /**
     * Set Foreman password.
     * @param password Secret.
     */
    public void setPassword(Secret password) {
        this.password = password;
    }

    /**
     * Get credentials for SSH connection.
     * @return credential id.
     */
    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * Get SSH connection time in seconds.
     * @return timeout in secs.
     */
    public Integer getSshConnectionTimeOut() {
        return sshConnectionTimeOut;
    }

    /**
     * Descriptor for Foreman Cloud.
     *
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Foreman Shared Node";
        }

        /**
         * Fill SSH credentials.
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
         * @param url url.
         * @param user user.
         * @param password password.
         * @return Foram Validation.
         * @throws ServletException if occurs.
         */
        public FormValidation doTestConnection(@QueryParameter("url") String url,
                @QueryParameter("user") String user,
                @QueryParameter("password") Secret password) throws ServletException {
            url = StringUtils.strip(StringUtils.stripToNull(url), "/");
            if (url != null && isValidURL(url)) {
                try {
                    String version = testConnection(url, user, password);
                    if (version != null) {
                        return FormValidation.okWithMarkup("<strong>Foreman version is " + version + "<strong>");
                    } else {
                        return FormValidation.error("Unhandled error in getting version from Foreman");
                    }
                } catch (LoginException e) {
                    return FormValidation.error(Messages.AuthFailure());
                } catch (Exception e) {
                    LOGGER.error("Unhandled exception in doTestConnection: ", e);
                    return FormValidation.error(Messages.Error() + ": " + e);
                }
            }
            return FormValidation.error(Messages.InvalidURI());
        }

        /**
         * Check for compatible hosts.
         * @param url url.
         * @param user user.
         * @param password password.
         * @return Form Validation.
         * @throws ServletException if occurs.
         */
        public FormValidation doCheckForCompatibleHosts(@QueryParameter("url") String url,
                @QueryParameter("user") String user,
                @QueryParameter("password") Secret password) throws ServletException {

            FormValidation testConn = this.doTestConnection(url, user, password);
            if (testConn.kind != FormValidation.Kind.OK) {
                return testConn;
            }

            Set<String> hosts = checkForCompatibleHosts(url, user, password);
            StringBuffer hostsMessage = new StringBuffer();
            hostsMessage.append("<b>The following hosts are compatible:</b> <small>(parameters JENKINS_LABEL, "
                    + "RESERVED, JENKINS_SLAVE_REMOTEFS_ROOT are defined)</small><br><br>");
            if (hosts == null || hosts.isEmpty()) {
                return FormValidation.error("NO hosts found that have defined parameters of JENKINS_LABEL,"
                        + " RESERVED, JENKINS_SLAVE_REMOTEFS_ROOT");
            } else {
                for (String host: hosts) {
                    hostsMessage.append("<font face=\"verdana\" color=\"green\">" + host + "</font><br>");
                }
                return FormValidation.okWithMarkup(hostsMessage.toString());
            }
        }

        /**
         * Call API to check for compatible hosts.
         * @param url url.
         * @param user user.
         * @param password password.
         * @return List of hosts.
         */
        private Set<String> checkForCompatibleHosts(String url, String user, Secret password) {
            ForemanAPI testApi = new ForemanAPI(url, user, password);
            Map<String, String> hosts = testApi.getCompatibleHosts();
            return hosts.keySet();
        }

        /**
         * Call API to test connection.
         * @param url url.
         * @param user user.
         * @param password password.
         * @return Foreman version.
         * @throws Exception if occurs.
         */
        private String testConnection(String url, String user, Secret password) throws Exception {
            url = StringUtils.strip(StringUtils.stripToNull(url), "/");
            if (url != null && isValidURL(url)) {
                ForemanAPI testApi = new ForemanAPI(url, user, password);
                return testApi.getVersion();
            }
            return null;
        }

        /**
         * Check if URL is valid.
         * @param url url.
         * @return true if valid.
         */
        private static boolean isValidURL(String url) {
            try {
                new URI(url);
            } catch (URISyntaxException e) {
                LOGGER.error("URISyntaxException, returning false.");
                return false;
            }
            return true;
        }
    }
}

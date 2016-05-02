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
 * Foreman Cloud implementation.
 *
 */
public class ForemanCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ForemanCloud.class);

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
     * The time in minutes to retain slave after it becomes idle.
     */
    private Integer retentionTime = null;

    private transient ForemanAPI api = null;
    private transient ForemanComputerLauncherFactory launcherFactory = null;

    /**
     * Constructor with name.
     * @param name Name of cloud.
     */
    public ForemanCloud(String name) {
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
     * @param retentionTime time in mins to terminate slave after
     *          it becomes idle.
     */
    @DataBoundConstructor
    public ForemanCloud(String cloudName, String url, String user, Secret password, String credentialsId,
            Integer retentionTime) {
        super(cloudName);

        this.cloudName = cloudName;
        this.url = url;
        this.user = user;
        this.password = password;
        this.credentialsId = credentialsId;
        this.retentionTime = retentionTime;
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
        return getForemanAPI().hasResources(label.toString());
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        Collection<NodeProvisioner.PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();
        if (getForemanAPI().hasAvailableResources(label.toString())) {
            result.add(new NodeProvisioner.PlannedNode(
                    label.toString(),
                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        public Node call() throws Exception {
                            try {
                                return provision(label);
                            } catch (Exception ex) {
                                LOGGER.error("Error in provisioning label='" + label.toString() + "'", ex);
                                throw ex;
                            }
                        }
                    }),
                    1));
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
    private ForemanSlave provision(Label label) throws Exception {
        LOGGER.info("Trying to provision Foreman slave for '" + label.toString() + "'");

        final JsonNode host = getForemanAPI().reserve(label.toString());
        if (host != null) {
            String name = null;
            try {
                name = host.get("name").asText();

                String remoteFS = getForemanAPI().getRemoteFSForSlave(name);
                String hostIP = getForemanAPI().getIPForHost(name);
                String hostForConnection = name;
                if (hostIP != null) {
                    hostForConnection = hostIP;
                }

                if (launcherFactory == null) {
                    launcherFactory = new ForemanSSHComputerLauncherFactory(hostForConnection,
                            SSH_DEFAULT_PORT, credentialsId);
                } else {
                    if (launcherFactory instanceof ForemanSSHComputerLauncherFactory) {
                        ((ForemanSSHComputerLauncherFactory)launcherFactory).configure(hostForConnection,
                                SSH_DEFAULT_PORT, credentialsId);
                    }
                }

                RetentionStrategy<AbstractCloudComputer> strategy = new CloudRetentionStrategy(retentionTime);

                List<? extends NodeProperty<?>> properties = Collections.emptyList();
                return new ForemanSlave(this.cloudName, host, name, hostForConnection, label.toString(), remoteFS,
                        launcherFactory.getForemanComputerLauncher(), strategy, properties);
            } catch (Exception e) {
                LOGGER.warn("Exception encountered when trying to create slave. "
                        + "Trying to release Foreman slave '" + name + "'");
                throw e;
            }
        }

        // Something has changed and there are now no resources available...
        throw new Exception("No Foreman resources available...");
    }

    /**
     * Get Cloud using provided name.
     * @param name Cloud name.
     * @return a Foreman Cloud.
     * @throws IllegalArgumentException if occurs.
     */
    public static ForemanCloud getByName(String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getInstance().clouds.getByName(name);
        if (cloud instanceof ForemanCloud) {
            return (ForemanCloud)cloud;
        }
        throw new IllegalArgumentException(name + " is not an Foreman cloud: " + cloud);
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
     * Get retention time in mins.
     * @return time.
     */
    public Integer getRetentionTime() {
        return retentionTime;
    }

    /**
     * Descriptor for Foreman Cloud.
     *
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Foreman";
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
                        return FormValidation.okWithMarkup("<string>Foreman version is " + version + "<strong>");
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

            List<String> hosts = checkForCompatibleHosts(url, user, password);
            StringBuffer hostsMessage = new StringBuffer();
            hostsMessage.append("<b>The following hosts are compatible:</b> <small>(parameters JENKINS_LABEL, "
                    + "RESERVED, JENKINS_SLAVE_REMOTEFS_ROOT are defined)</small><br><br>");
            for (String host: hosts) {
                hostsMessage.append("<font face=\"verdana\" color=\"green\">" + host + "</font><br>");
            }
            if (hosts == null || hosts.isEmpty()) {
                return FormValidation.error("NO hosts found that have defined parameters of JENKINS_LABEL,"
                        + " RESERVED, JENKINS_SLAVE_REMOTEFS_ROOT");
            } else {
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
        private List<String> checkForCompatibleHosts(String url, String user, Secret password) {
            ForemanAPI testApi = new ForemanAPI(url, user, password);
            return testApi.getCompatibleHosts();
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

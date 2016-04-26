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

public class ForemanCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ForemanCloud.class);

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
    public String credentialsId = null;

    private transient ForemanAPI api = null;
    private transient ForemanComputerLauncherFactory launcherFactory = null;

    public ForemanCloud(String name) {
        super(name);
        this.cloudName = name;
    }

    @DataBoundConstructor
    public ForemanCloud(String cloudName, String url, String user, Secret password) {
        super(cloudName);

        this.cloudName = cloudName;
        this.url = url;
        this.user = user;
        this.password = password;
        api = new ForemanAPI(this.url, this.user, this.password);
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public void setLauncherFactory(ForemanComputerLauncherFactory launcherFactory) {
        this.launcherFactory = launcherFactory;
    }

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

    private ForemanSlave provision(Label label) throws Exception {
        LOGGER.info("Trying to provision Foreman slave for '" + label.toString() + "'");

        final JsonNode host = getForemanAPI().reserve(label.toString());
        if (host != null) {
            String name = null;
            try {
                name = host.get("name").asText();

                String remoteFS = getForemanAPI().getRemoteFSForSlave(name);
                String hostIP = getForemanAPI().getIPForSlave(name);
                String hostForConnection = name;
                if (hostIP != null) {
                    hostForConnection = hostIP;
                }

                if (launcherFactory == null) {
                    launcherFactory = new ForemanSSHComputerLauncherFactory(hostForConnection, 22, credentialsId);
                } else {
                    if (launcherFactory instanceof ForemanSSHComputerLauncherFactory) {
                        ((ForemanSSHComputerLauncherFactory)launcherFactory).configure(hostForConnection, 22, credentialsId);
                    }
                }

                RetentionStrategy<AbstractCloudComputer> strategy = new CloudRetentionStrategy(1);

                List<? extends NodeProperty<?>> properties = Collections.emptyList();
                return new ForemanSlave(this.cloudName, host, name, hostForConnection, label.toString(), remoteFS,
                        launcherFactory.getForemanComputerLauncher(), strategy, properties);
            }
            catch (Exception e) {
                LOGGER.warn("Exception encountered when trying to create slave. Trying to release Foreman slave '" + name + "'");
                throw e;
            }
        }

        // Something has changed and there are now no resources available...
        throw new NoForemanResourceAvailableException();
    }

    public static ForemanCloud getByName(String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getInstance().clouds.getByName(name);
        if (cloud instanceof ForemanCloud) return (ForemanCloud) cloud;
        throw new IllegalArgumentException(name + " is not an Foreman cloud: " + cloud);
    }

    public String getCloudName() {
        return cloudName;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public Secret getPassword() {
        return password;
    }

    public void setPassword(Secret password) {
        this.password = password;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Foreman";
        }

        public ListBoxModel doFillCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(anyOf(
                                instanceOf(SSHUserPrivateKey.class),
                                instanceOf(UsernamePasswordCredentials.class)),
                            CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class));
        }

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

        public FormValidation doCheckForCompatibleHosts(@QueryParameter("url") String url,
                @QueryParameter("user") String user,
                @QueryParameter("password") Secret password) throws ServletException {

            FormValidation testConn = this.doTestConnection(url, user, password);
            if (testConn.kind != FormValidation.Kind.OK) {
                return testConn;
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            List<String> hosts = checkForCompatibleHosts(url, user, password);
            StringBuffer hostsMessage = new StringBuffer();
            hostsMessage.append("<u>The following hosts have the parameters JENKINS_LABEL, RESERVED, JENKINS_SLAVE_REMOTEFS_ROOT</u><br>");
            for (String host: hosts) {
                hostsMessage.append("<b>" + host + "</b><br>");
            }
            if (hosts == null || hosts.isEmpty()) {
                return FormValidation.error("NO hosts found that have defined parameters of JENKINS_LABEL, RESERVED, JENKINS_SLAVE_REMOTEFS_ROOT");
            } else {
                return FormValidation.okWithMarkup(hostsMessage.toString());
            }
        }

        private List<String> checkForCompatibleHosts(String url, String user, Secret password) {
            ForemanAPI testApi = new ForemanAPI(url, user, password);
            return testApi.getCompatibleHosts();
        }

        private String testConnection(String url, String user, Secret password) throws Exception {
            url = StringUtils.strip(StringUtils.stripToNull(url), "/");
            if (url != null && isValidURL(url)) {
                ForemanAPI testApi = new ForemanAPI(url, user, password);
                return testApi.getVersion();
            }
            return null;
        }

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

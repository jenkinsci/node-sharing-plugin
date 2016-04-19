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
import hudson.util.Secret;

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
import org.kohsuke.stapler.QueryParameter;

import com.fasterxml.jackson.databind.JsonNode;

public class ForemanCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ForemanCloud.class);

    private String cloudName;
    private String url;
    private String user;
    private Secret password;

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

                if (launcherFactory == null) {
                    launcherFactory = new ForemanSSHComputerLauncherFactory(name, 22);
                } else {
                    if (launcherFactory instanceof ForemanSSHComputerLauncherFactory) {
                        ((ForemanSSHComputerLauncherFactory)launcherFactory).configure(name, 22);
                    }
                }

                RetentionStrategy<AbstractCloudComputer> strategy = new CloudRetentionStrategy(1);

                List<? extends NodeProperty<?>> properties = Collections.emptyList();
                return new ForemanSlave(this.cloudName, host, name, name, label.toString(), remoteFS,
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

        public FormValidation doTestConnection(@QueryParameter("url") String url,
                @QueryParameter("user") String user,
                @QueryParameter("password") Secret password) throws ServletException {
            url = StringUtils.strip(StringUtils.stripToNull(url), "/");
            if (url != null && isValidURL(url)) {
                try {
                    if (testConnection(url, user, password)) {
                        return FormValidation.ok(Messages.Success());
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

        private boolean testConnection(String url, String user, Secret password) throws Exception {
            url = StringUtils.strip(StringUtils.stripToNull(url), "/");
            if (url != null && isValidURL(url)) {
                ForemanAPI testApi = new ForemanAPI(url, user, password);
                testApi.getHosts();
                return true;
            }
            return false;

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

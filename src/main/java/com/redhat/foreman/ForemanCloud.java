package com.redhat.foreman;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.Secret;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.security.auth.login.LoginException;
import javax.servlet.ServletException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import jenkins.model.Jenkins;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.fasterxml.jackson.databind.JsonNode;

public class ForemanCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(ForemanCloud.class);

    private String url = "http://10.8.48.62:32768/api";
    private String user = "admin";
    private Secret password = Secret.fromString("changeme");

    private transient ForemanAPI api = null;

    @DataBoundConstructor
    public ForemanCloud(String name, String url, String user, Secret password) {
        super(name);

        this.url = url;
        this.user = user;
        this.password = password;
        api = new ForemanAPI(this.url, this.user, this.password);
    }

    public ForemanAPI getForemanAPI() {
        return api;
    }

    @Override
    public boolean canProvision(Label label) {
        return api.hasResources(label.toString());
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        Collection<NodeProvisioner.PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();
        if (api.hasAvailableResources(label.toString())) {
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

    private ForemanSlave provision(Label label) throws IOException, Descriptor.FormException, ExecutionException {
        LOGGER.info("Trying to provision Foreman slave for '" + label.toString() + "'");

        final JsonNode host = api.reserve(label.toString());
        if (host != null) {
            String name = host.get("name").asText();
            String description = host.get("name").asText();
            String remoteFS = "/";
            SSHLauncher launcher = null;
            RetentionStrategy<Computer> strategy = RetentionStrategy.NOOP;
            List<? extends NodeProperty<?>> properties = null;
            return new ForemanSlave(this.name, host, name, description, label.toString(), remoteFS, launcher, strategy, properties);
        }

        // Something has changed and there are now no resources available...
        throw new NoForemanResourceAvailableException();
    }

    public static ForemanCloud getByName(String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getInstance().clouds.getByName(name);
        if (cloud instanceof ForemanCloud) return (ForemanCloud) cloud;
        throw new IllegalArgumentException(name + " is not an Foreman cloud: " + cloud);
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
                ClientConfig config = new ClientConfig();

                HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, Secret.toString(password));
                config.register( feature) ;

                config.register(JacksonFeature.class);

                Client client = ClientBuilder.newClient(config);
                WebTarget webTarget = client.target(url).path("v2/hosts");

                Invocation.Builder invocationBuilder =  webTarget.request(MediaType.APPLICATION_JSON);
                Response response = invocationBuilder.get();

                return response.getStatus() == 200;
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

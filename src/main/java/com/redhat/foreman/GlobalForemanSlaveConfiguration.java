package com.redhat.foreman;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.Secret;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.login.LoginException;
import javax.servlet.ServletException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;

import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public final class GlobalForemanSlaveConfiguration extends GlobalConfiguration {

    private static final String PLUGIN_NAME = Messages.PluginName();

    private static final Logger log = Logger.getLogger(GlobalForemanSlaveConfiguration.class.getName());

    private String url = "http://10.8.48.62:32768/api";
    private String user = "admin";
    private Secret password = Secret.fromString("changeme");
    private String path = "v2/hosts";

    @DataBoundConstructor
    public GlobalForemanSlaveConfiguration(String url, String user, Secret password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public GlobalForemanSlaveConfiguration() {
        load();
    }

    @Override
    public String getDisplayName() {
        return PLUGIN_NAME;
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws hudson.model.Descriptor.FormException {
        req.bindJSON(this, json);

        try {
	        if (isValidURL(url) && testConnection(url, user, password)) {
	            save();
	            return true;
	        }
        } catch (Exception e) {
	        log.log(Level.SEVERE, "EXCEPTION IN CONFIGURE, returning false!", e);
        }
        return false;
    }
    public String getUrl() {
        return url;
    }

    public void setUrl(final String url) {
        this.url = StringUtils.strip(StringUtils.stripToNull(url), "/");
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

    public void setPassword(String password) {
        this.password = Secret.fromString(password);
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
    			log.log(Level.SEVERE, "Unhandled exception in doTestConnection: ", e);
                return FormValidation.error(Messages.Error() + ": " + e);
			}
        }
        return FormValidation.error(Messages.InvalidURI());
    }

    public static GlobalForemanSlaveConfiguration get() {
        return GlobalConfiguration.all().get(GlobalForemanSlaveConfiguration.class);
    }

    private boolean testConnection(String url, String user, Secret password) throws Exception {
        url = StringUtils.strip(StringUtils.stripToNull(url), "/");
        if (url != null && isValidURL(url)) {
            ClientConfig config = new ClientConfig();

            HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, Secret.toString(password));
            config.register( feature) ;

            config.register(JacksonFeature.class);

            Client client = ClientBuilder.newClient(config);
            WebTarget webTarget = client.target(url).path(path);

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
			log.log(Level.SEVERE, "URISyntaxException, returning false.");
            return false;
        }
        return true;
    }
}

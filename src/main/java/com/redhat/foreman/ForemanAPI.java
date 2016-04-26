package com.redhat.foreman;

import hudson.util.Secret;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Foreman API.
 *
 */
public class ForemanAPI {
    private static final Logger LOGGER = Logger.getLogger(ForemanAPI.class);;

    private static final String JENKINS_LABEL = "JENKINS_LABEL";
    private static final String FOREMAN_HOSTS_PATH = "v2/hosts";
    private static final String FOREMAN_RESERVE_PATH = "hosts_reserve";
    private static final String FOREMAN_RELEASE_PATH = "hosts_release";

    private static final String FOREMAN_SEARCH_PARAM         = "search";
    private static final String FOREMAN_SEARCH_LABELPARAM    = "params." + JENKINS_LABEL;
    private static final String FOREMAN_SEARCH_LABEL         = FOREMAN_SEARCH_LABELPARAM + "=";
    private static final String FOREMAN_SEARCH_RESERVEDPARAM = "params.RESERVED";
    private static final String FOREMAN_SEARCH_FREE          = FOREMAN_SEARCH_RESERVEDPARAM + "=false";

    private static final String FOREMAN_QUERY_PARAM = "query";
    private static final String FOREMAN_QUERY_NAME = "name ~ ";

    private static final String FOREMAN_RESERVE_REASON = "reason";

    private static final String JENKINS_SLAVE_REMOTEFS_ROOT = "JENKINS_SLAVE_REMOTEFS_ROOT";
    private static final String FOREMAN_REMOTEFS_ROOT = "params." + JENKINS_SLAVE_REMOTEFS_ROOT;

    private static final String FOREMAN_STATUS_PATH = "v2/status";

    private WebTarget base = null;

    /**
     * Foreman API Constructor.
     * @param url foreman url.
     * @param user user.
     * @param password password.
     */
    public ForemanAPI(String url, String user, Secret password) {
        ClientConfig clientConfig = new ClientConfig();
        HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, Secret.toString(password));
        clientConfig.register(feature);
        clientConfig.register(JacksonFeature.class);
        Client client = ClientBuilder.newClient(clientConfig);
        base = client.target(url);
    }

    /**
     * Check Foreman if resources exist for the label.
     * @param label Jenkins label to check for.
     * @return true if Foreman has resources for this label.
     */
    public boolean hasResources(String label) {
        WebTarget target = base.path(FOREMAN_HOSTS_PATH).queryParam(FOREMAN_SEARCH_PARAM, FOREMAN_SEARCH_LABEL + label);
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        LOGGER.info(target.toString());
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = mapper.readValue(responseAsString, Map.class);
                if (json.containsKey("subtotal") && (Integer)json.get("subtotal") > 0) {
                    return true;
                }
            } catch (Exception e) {
                LOGGER.debug(e.getMessage(), e);
                return false;
            }
        }
        return false;
    }

    /**
     * Check Foreman if resources are available for label.
     * Also checks if JENKINS_SLAVE_REMOTEFS_ROOT is defined.
     * @param label Jenkins labels to check for.
     * @return true if resources available.
     */
    public boolean hasAvailableResources(String label) {
        WebTarget target = base.path(FOREMAN_HOSTS_PATH)
                .queryParam(FOREMAN_SEARCH_PARAM, FOREMAN_SEARCH_LABEL + label
                + " and " + FOREMAN_SEARCH_FREE
                + " and has " + FOREMAN_REMOTEFS_ROOT);
        LOGGER.info(target.toString());
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = mapper.readValue(responseAsString, Map.class);
                if (json.containsKey("subtotal") && (Integer)json.get("subtotal") > 0) {
                    return true;
                }
            } catch (Exception e) {
                LOGGER.debug(e.getMessage(), e);
                return false;
            }
        }
        return false;
    }

    /**
     * Reserve the Foreman resource.
     * @param label Label to use.
     * @return host in json form.
     */
    public JsonNode reserve(String label) {
        WebTarget target = base.path(FOREMAN_HOSTS_PATH)
                .queryParam(FOREMAN_SEARCH_PARAM, FOREMAN_SEARCH_LABEL + label
                        + " and " + FOREMAN_SEARCH_FREE);
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        LOGGER.info(target.toString());
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readValue(responseAsString, JsonNode.class);
                JsonNode hosts = json.get("results");
                if (hosts != null && hosts.isArray()) {
                    for (JsonNode host : hosts) {
                        if (reserve(host) != null) {
                            return host;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Unhandled exception retrieving available resources for '" + label + "'.", e);
            }
        } else {
            LOGGER.error("Retrieving available resources for '" + label
                    + "' returned code " + response.getStatus() + ".");
        }
        return null;
    }

    /**
     * Reserve host outright.
     * @param host resource in Foreman.
     * @return host in json form.
     */
    private JsonNode reserve(JsonNode host) {
        String hostname = host.get("name").asText();
        WebTarget target = base.path(FOREMAN_RESERVE_PATH)
                .queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname)
                .queryParam(FOREMAN_RESERVE_REASON, "Reserved for " + Jenkins.getInstance().getRootUrl());
        LOGGER.info(target.toString());
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                return new ObjectMapper().readValue(responseAsString, JsonNode.class);
            } catch (Exception e) {
                LOGGER.error("Unhandled exception reserving " + hostname + ".", e);
            }
        } else {
            LOGGER.error("Attempt to reserve " + hostname + " returned code " + response.getStatus() + ".");
        }
        return null;
    }

    /**
     * Release host from Foreman.
     * @param hostname name of host to release.
     */
    public void release(String hostname) {
        WebTarget target = base.path(FOREMAN_RELEASE_PATH)
                .queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname);
        LOGGER.info(target.toString());
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) != Response.Status.OK) {
            LOGGER.error("Attempt to release " + hostname + " returned code " + response.getStatus() + ".");
        }
    }

    /**
     * Get Foreman version.
     * @return version.
     * @throws Exception if occurs.
     */
    public String getVersion() throws Exception {
        WebTarget target = base.path(FOREMAN_STATUS_PATH);
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode param = mapper.readValue(responseAsString, JsonNode.class);
            LOGGER.info(param.toString());
            if ((param.get("version") != null)) {
                return param.get("version").asText();
            }
        }
        return null;
    }

    /**
     * General utility method to get parameter value for host.
     * @param hostname name of host.
     * @param parameterName name of param.
     * @return value.
     */
    public String getHostParamterValue(String hostname, String parameterName) {
        String hostParamPath = FOREMAN_HOSTS_PATH + "/" + hostname + "/parameters/" + parameterName;
        WebTarget target = base.path(hostParamPath);
        LOGGER.info(target.toString());
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode param = mapper.readValue(responseAsString, JsonNode.class);
                LOGGER.info(param.toString());
                if ((param.get("name") != null && param.get("name").textValue().equals(parameterName))) {
                    LOGGER.info(param.get("value"));
                    return param.get("value").asText();
                }
            } catch (Exception e) {
                LOGGER.error("Unhandled exception getting " + parameterName + " for " + hostname + ".", e);
            }
        } else {
            LOGGER.error("Retrieving " + parameterName + " for " + hostname
                    + " returned code " + response.getStatus() + ".");
        }
        return null;
    }
    /**
     * Get Jenkins Slave Remote FS root.
     * @param hostname name of host.
     * @return value of slave remote FS root.
     */
    public String getRemoteFSForSlave(String hostname) {
        return getHostParamterValue(hostname, JENKINS_SLAVE_REMOTEFS_ROOT);
    }

    /**
     * Get value for host attribute.
     * @param hostname name of host.
     * @param attribute attrib to look for.
     * @return value of attrib.
     */
    public String getHostAttributeValue(String hostname, String attribute) {
        String hostParamPath = FOREMAN_HOSTS_PATH + "/" + hostname;
        WebTarget target = base.path(hostParamPath);
        LOGGER.info(target.toString());
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode param = mapper.readValue(responseAsString, JsonNode.class);
                LOGGER.info(param.toString());
                LOGGER.info(param.get(attribute));
                return param.get(attribute).asText();
            } catch (Exception e) {
                LOGGER.error("Unhandled exception getting " + attribute + " for " + hostname + ".", e);
            }
        } else {
            LOGGER.error("Retrieving " + attribute + " for " + hostname
                    + " returned code " + response.getStatus() + ".");
        }
        return null;
    }

    /** Get IP for Host.
     * @param hostname name of host.
     * @return IP.
     */
    public String getIPForHost(String hostname) {
        return getHostAttributeValue(hostname, "ip");
    }

    /**
     * Get hosts based on query.
     * @param query query string.
     * @return list of hosts.
     */
    public List<String> getHostForQuery(String query) {
        ArrayList<String> hostsList = new ArrayList<String>();
        WebTarget target = base.path(FOREMAN_HOSTS_PATH)
                .queryParam(FOREMAN_SEARCH_PARAM,
                  query);

        LOGGER.info(target.toString());
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readValue(responseAsString, JsonNode.class);
                JsonNode hosts = json.get("results");
                if (hosts != null && hosts.isArray()) {
                    for (JsonNode host : hosts) {
                        hostsList.add(host.get("name").asText());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Unhandled exception getting compatible hosts", e);
            }
        }
        return hostsList;
    }

    /**
     * Get list of compatible hosts.
     * @return list of host names.
     */
    public List<String> getCompatibleHosts() {
        String query = "has " + FOREMAN_SEARCH_LABELPARAM
                + " and has " + FOREMAN_SEARCH_RESERVEDPARAM
                + " and has " + FOREMAN_REMOTEFS_ROOT;
        return getHostForQuery(query);
    }
}

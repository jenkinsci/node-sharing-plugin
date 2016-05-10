package com.redhat.foreman;

import hudson.util.Secret;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.HashMap;
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
    private static final String FOREMAN_SEARCH_RESERVEDPARAM = "params.RESERVED";

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
     * Reserve host outright.
     * @param hostname resource in Foreman.
     * @return host in json form.
     */
    public JsonNode reserveHost(String hostname) {
        LOGGER.info("Reserving host " + hostname);
        WebTarget target = base.path(FOREMAN_RESERVE_PATH)
                .queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname)
                .queryParam(FOREMAN_RESERVE_REASON, "Reserved for " + Jenkins.getInstance().getRootUrl());
        LOGGER.debug(target.toString());
        Response response = getForemanResponse(target);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.debug(responseAsString);
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
        LOGGER.info("Releasing host " + hostname);
        WebTarget target = base.path(FOREMAN_RELEASE_PATH)
                .queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname);
        LOGGER.debug(target.toString());
        Response response = getForemanResponse(target);

        if (Response.Status.fromStatusCode(response.getStatus()) != Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.debug(responseAsString);
            LOGGER.error("Attempt to release " + hostname + " returned code " + response.getStatus() + ".");
        }
    }

    /**
     * Gracefully handle getting a response from Foreman.
     * @param target WebTarget to get.
     * @return Response. 500 error is the default.
     */
    private Response getForemanResponse(WebTarget target) {
        Response response = Response.serverError().entity(new String("error")).build();
        try {
            response = target.request(MediaType.APPLICATION_JSON).get();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
        return response;
    }

    /**
     * Get Foreman version.
     * @return version.
     * @throws Exception if occurs.
     */
    public String getVersion() throws Exception {
        WebTarget target = base.path(FOREMAN_STATUS_PATH);
        Response response = getForemanResponse(target);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.debug(responseAsString);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode param = mapper.readValue(responseAsString, JsonNode.class);
            LOGGER.debug(param.toString());
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
    public String getHostParameterValue(String hostname, String parameterName) {
        String hostParamPath = FOREMAN_HOSTS_PATH + "/" + hostname + "/parameters/" + parameterName;
        WebTarget target = base.path(hostParamPath);
        LOGGER.debug(target.toString());
        Response response = getForemanResponse(target);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.debug(responseAsString);
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode param = mapper.readValue(responseAsString, JsonNode.class);
                LOGGER.debug(param.toString());
                if ((param.get("name") != null && param.get("name").textValue().equals(parameterName))) {
                    LOGGER.debug("Returning host parameter "
                            + parameterName + "=" + param.get("value") + " for " + hostname);
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
        return getHostParameterValue(hostname, JENKINS_SLAVE_REMOTEFS_ROOT);
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
        LOGGER.debug(target.toString());
        Response response = getForemanResponse(target);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.debug(responseAsString);
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode param = mapper.readValue(responseAsString, JsonNode.class);
                LOGGER.debug(param.toString());
                LOGGER.debug(param.get(attribute));
                LOGGER.info("Retrieving " + attribute + "=" + param.get(attribute) + " for " + hostname);
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
    public Map<String, String> getHostForQuery(String query) {
        Map<String, String> hostsMap = new HashMap<String, String>();
        List<String> hostsList = new ArrayList<String>();
        WebTarget target = base.path(FOREMAN_HOSTS_PATH)
                .queryParam(FOREMAN_SEARCH_PARAM,
                  query);

        LOGGER.debug(target.toString());
        Response response = getForemanResponse(target);

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.debug(responseAsString);
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
            for (String host: hostsList) {
                String labelsAsString = getHostParameterValue(host, JENKINS_LABEL);
                if (labelsAsString != null) {
                    hostsMap.put(host, labelsAsString);
                }
            }
        }
        return hostsMap;
    }

    /**
     * Get list of compatible hosts.
     * @return list of host names.
     */
    public Map<String, String> getCompatibleHosts() {
        String query = "has " + FOREMAN_SEARCH_LABELPARAM
                + " and has " + FOREMAN_SEARCH_RESERVEDPARAM
                + " and has " + FOREMAN_REMOTEFS_ROOT;
        return getHostForQuery(query);
    }

    /**
     * Get Host's Jenkins labels.
     * @param hostName name of host.
     * @return value of label parameter.
     */
    public String getLabelsForHost(String hostName) {
        return getHostParameterValue(hostName, JENKINS_LABEL);
    }
}

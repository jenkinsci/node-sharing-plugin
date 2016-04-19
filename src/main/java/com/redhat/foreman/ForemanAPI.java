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

public class ForemanAPI {
    private static final Logger LOGGER = Logger.getLogger(ForemanAPI.class);;

    public static final String JENKINS_LABEL = "JENKINS_LABEL";

    public static final String FOREMAN_HOSTS_PATH = "v2/hosts";
    public static final String FOREMAN_RESERVE_PATH = "hosts_reserve";
    public static final String FOREMAN_RELEASE_PATH = "hosts_release";

    public static final String FOREMAN_SEARCH_PARAM = "search";
    public static final String FOREMAN_SEARCH_LABEL = "params." + JENKINS_LABEL + "=";
    public static final String FOREMAN_SEARCH_FREE = "params.RESERVED=false";

    public static final String FOREMAN_QUERY_PARAM = "query";
    public static final String FOREMAN_QUERY_NAME = "name ~ ";

    public static final String FOREMAN_RESERVE_REASON = "reason";

    private WebTarget base = null;

    public ForemanAPI(String url, String user, Secret password) {
        ClientConfig clientConfig = new ClientConfig();
        HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, Secret.toString(password));
        clientConfig.register(feature);
        clientConfig.register(JacksonFeature.class);
        Client client = ClientBuilder.newClient(clientConfig);
        base = client.target(url);
    }

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
            }
        }
        return false;
    }

    public boolean hasAvailableResources(String label) {
        WebTarget target = base.path(FOREMAN_HOSTS_PATH).queryParam(FOREMAN_SEARCH_PARAM, FOREMAN_SEARCH_LABEL + label + " and " + FOREMAN_SEARCH_FREE);
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
            }
        }
        return false;
    }

    public JsonNode reserve(String label) {
        WebTarget target = base.path(FOREMAN_HOSTS_PATH).queryParam(FOREMAN_SEARCH_PARAM, FOREMAN_SEARCH_LABEL + label + " and " + FOREMAN_SEARCH_FREE);
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
            LOGGER.error("Retrieving available resources for '" + label + "' returned code " + response.getStatus() + ".");
        }
        return null;
    }

    private JsonNode reserve(JsonNode host) {
        String hostname = host.get("name").asText();
        WebTarget target = base.path(FOREMAN_RESERVE_PATH).queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname).
                queryParam(FOREMAN_RESERVE_REASON, "Reserved for " + Jenkins.getInstance().getRootUrl());
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

    public void release(String hostname) {
        WebTarget target = base.path(FOREMAN_RELEASE_PATH).queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname);
        LOGGER.info(target.toString());
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) != Response.Status.OK) {
            LOGGER.error("Attempt to release " + hostname + " returned code " + response.getStatus() + ".");
        }
    }

    public List<String> getHosts() throws Exception {
        List<String> hosts = new ArrayList<String>();
        WebTarget target = base.path(FOREMAN_HOSTS_PATH);
        Response response = target.request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);

        if (Response.Status.fromStatusCode(response.getStatus()) != Response.Status.OK) {
            throw new Exception("Attempt to list hosts failed - status: " + response.getStatus());
        }

        return hosts;
    }

    public String getRemoteFSForSlave() {
        return "/tmp";
    }
}

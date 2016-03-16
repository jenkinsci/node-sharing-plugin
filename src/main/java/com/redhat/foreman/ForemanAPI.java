package com.redhat.foreman;

import hudson.util.Secret;

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

    private static final String JENKINS_LABEL = "JENKINS_LABEL";

    private static final String FOREMAN_HOSTS_PATH = "v2/hosts";
    private static final String FOREMAN_RESERVE_PATH = "hosts_reserve";
    private static final String FOREMAN_RELEASE_PATH = "hosts_release";

    private static final String FOREMAN_SEARCH_PARAM = "search";
    private static final String FOREMAN_SEARCH_LABEL = "params." + JENKINS_LABEL + "=";
    private static final String FOREMAN_SEARCH_FREE = "params.RESERVED=false";

    private static final String FOREMAN_QUERY_PARAM = "query";
    private static final String FOREMAN_QUERY_NAME = "params.name ~ ";

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

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = mapper.readValue(response.readEntity(String.class), Map.class);
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
        Response response = target.request(MediaType.APPLICATION_JSON).get();

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = mapper.readValue(response.readEntity(String.class), Map.class);
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

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readValue(response.readEntity(String.class), JsonNode.class);
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
        WebTarget target = base.path(FOREMAN_RESERVE_PATH).queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname);
        Response response = target.request(MediaType.APPLICATION_JSON).get();

        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                return new ObjectMapper().readValue(response.readEntity(String.class), JsonNode.class);
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
        Response response = target.request(MediaType.APPLICATION_JSON).get();

        if (response.getStatus() != 200) {
            LOGGER.error("Attempt to release " + hostname + " returned code " + response.getStatus() + ".");
        }
    }
}

package com.redhat.foreman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Util;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.jersey.client.ClientProperties;

/**
 * Foreman API.
 *
 */
public class ForemanAPI {

    private static final Logger LOGGER = Logger.getLogger(ForemanAPI.class.getName());;

    private static final String FOREMAN_HOSTS_PATH = "v2/hosts";
    private static final String FOREMAN_RESERVE_PATH = "hosts_reserve";
    private static final String FOREMAN_RELEASE_PATH = "hosts_release";
    private static final String FOREMAN_SHOW_RESERVED_PATH = "show_reserved";
    private static final String FOREMAN_SEARCH_PARAM         = "search";

    /*package*/ static final String JENKINS_LABEL = "JENKINS_LABEL";
    private static final String FOREMAN_SEARCH_LABELPARAM    = "params." + JENKINS_LABEL;
    /*package*/ static final String FOREMAN_SEARCH_RESERVEDPARAMNAME = "RESERVED";
    private static final String FOREMAN_SEARCH_RESERVEDPARAM = "params." + FOREMAN_SEARCH_RESERVEDPARAMNAME;
    /*package*/ static final String JENKINS_SLAVE_REMOTEFS_ROOT = "JENKINS_SLAVE_REMOTEFS_ROOT";
    private static final String FOREMAN_REMOTEFS_ROOT = "params." + JENKINS_SLAVE_REMOTEFS_ROOT;

    private static final String FOREMAN_QUERY_PARAM = "query";
    private static final String FOREMAN_QUERY_NAME = "name ~ ";

    private static final String FOREMAN_RESERVE_REASON = "reason";

    private static final String FOREMAN_STATUS_PATH = "v2/status";

    private WebTarget base = null;

    /**
     * Foreman API Constructor.
     *
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

        // Define a quite defensive timeouts
        client.property(ClientProperties.CONNECT_TIMEOUT, 60000);   // 60s
        client.property(ClientProperties.READ_TIMEOUT,    300000);  // 5m
        base = client.target(url);
    }

    /**
     * Reserve host outright.
     *
     * @return Updated HostInfo representing reserved host.
     */
    @CheckForNull
    public HostInfo reserveHost(HostInfo host) throws Exception {
        String hostname = host.getName();
        host = getHostInfo(hostname); // Get fresh reserved status.
        if (host == null || host.isReserved()) return null;

        LOGGER.info("Reserving host " + hostname);
        String reserveReason = getReserveReason();
        WebTarget target = base.path(FOREMAN_RESERVE_PATH)
                .queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostname)
                .queryParam(FOREMAN_RESERVE_REASON, reserveReason);
        LOGGER.fine(target.toString());

        Response response = getForemanResponse(target);

        Response.Status status = Response.Status.fromStatusCode(response.getStatus());
        if (status == Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.finer(responseAsString);
            // It seems that response to reservation request is the old value in incompatible structure so we need a new request
            HostInfo reservedHost = getHostInfo(hostname);
            if (reserveReason.equals(reservedHost.getReservedFor())) {
                // Host reserved for this instance
                return reservedHost;
            }
            LOGGER.info("Unable to reserve " + hostname + ". " + reservedHost.getReservedFor());
            return null;
        } else {
            String msg = "Attempt to reserve " + hostname + " returned code " + response.getStatus() + ".";
            LOGGER.severe(msg);

            // Ruby/Foreman's possible responses (JENKINS-39481)
            if (status == Response.Status.NOT_FOUND || status == Response.Status.NOT_ACCEPTABLE) {
                return null;
            } else {
                throw new Exception(msg);
            }
        }
    }

    /**
     * Reserve reason.
     *
     * @return string to be used for reserving.
     */
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    /*package for testing*/ static String getReserveReason() {
        String url = Jenkins.getInstance().getRootUrl();
        return "Reserved for " + url;
    }

    /**
     * Release host from Foreman.
     *
     * @param hostName name of host to release.
     */
    public void release(@Nonnull String hostName) throws ActionFailed {
        HostInfo hostInfo = getHostInfo(hostName);
        // Host is gone - nothing to release
        if (hostInfo == null) {
            LOGGER.info("Unable to release host " + hostName + ".  Does not seem to be in Foreman any longer.");
            return;
        }

        // We do not own the host - noop
        if (!getReserveReason().equals(hostInfo.getReservedFor())) {
            LOGGER.info("Unable to release host " + hostName + ".  Reserved for " + hostInfo.getReservedFor());
            return;
        }

        LOGGER.info("Attempting to Release host " + hostName);
        WebTarget target = base.path(FOREMAN_RELEASE_PATH)
                .queryParam(FOREMAN_QUERY_PARAM, FOREMAN_QUERY_NAME + hostName);
        LOGGER.finer(target.toString());
        Response response = getForemanResponse(target);

        if (Response.Status.fromStatusCode(response.getStatus()) != Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.finer(responseAsString);
            throw new CommunicationError(
                    "Attempt to release " + hostName + " returned code " + response.getStatus() + ":" + responseAsString
            );
        }

        LOGGER.info("Host " + hostName + " successfully released.");
    }

    /**
     * Gracefully handle getting a response from Foreman.
     *
     * @param target WebTarget to get.
     * @return Response. 500 error is the default.
     */
    private Response getForemanResponse(WebTarget target) {
        Response response = Response.serverError().entity("error").build();
        try {
            long time = System.currentTimeMillis();
            response = target.request(MediaType.APPLICATION_JSON).get();
            LOGGER.finer("getForemanResponse() response time from Foreman: " + Util.getTimeSpanString(System.currentTimeMillis() - time)
                    + " for URI: '" +target.getUri() + "'");
        } catch (Exception e) {
            LOGGER.severe(e.getMessage());
        }
        return response;
    }

    /**
     * Get Foreman version.
     *
     * @return version string.
     */
    @Nonnull
    public String getVersion() throws ActionFailed {
        WebTarget target = base.path(FOREMAN_STATUS_PATH);
        Response response = getForemanResponse(target);

        String responseAsString = response.readEntity(String.class);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            LOGGER.finer(responseAsString);
            ObjectMapper mapper = new ObjectMapper();
            try {
                JsonNode param = mapper.readValue(responseAsString, JsonNode.class);
                LOGGER.finer(param.toString());
                if ((param.get("version") != null)) {
                    String version = Util.fixEmptyAndTrim(param.get("version").asText());
                    if (version != null) return version;
                }
            } catch (IOException e) {
                throw new ProtocolMismatch("Unable to extract version from: " + responseAsString, e);
            }
            throw new ProtocolMismatch("Unable to extract version from: " + responseAsString);
        } else {
            throw new CommunicationError("Request failed with " + response.getStatus() + ": " + responseAsString);
        }
    }

    /**
     * Get host info.
     *
     * @return HostInfo of or null in case foreman does no longer have this host configured.
     */
    @CheckForNull
    public HostInfo getHostInfo(@Nonnull String hostname) throws ActionFailed {
        String hostParamPath = FOREMAN_HOSTS_PATH + "/" + hostname;
        WebTarget target = base.path(hostParamPath);
        LOGGER.finer(target.toString());
        Response response = getForemanResponse(target);

        String responseAsString = response.readEntity(String.class);
        Response.Status status = Response.Status.fromStatusCode(response.getStatus());
        if (status == Response.Status.OK) {
            LOGGER.finer(responseAsString);
            try {
                return new ObjectMapper().readerFor(HostInfo.class).readValue(responseAsString);
            } catch (IOException e) {
                throw new ProtocolMismatch(responseAsString, e);
            }
        }

        if (status == Response.Status.NOT_FOUND) return null;

        throw new CommunicationError(
                "Retrieving host info for " + hostname + " returned code " + response.getStatus() + ":" + responseAsString
        );
    }

    /**
     * Get hosts based on query.
     *
     * @param query query string.
     * @return list of hosts.
     * @throws Exception if occurs.
     */
    @Nonnull
    private Map<String, HostInfo> getHostsForQuery(String query) throws Exception {
        Map<String, HostInfo> hostsMap = new HashMap<String, HostInfo>();
        WebTarget target = base.path(FOREMAN_HOSTS_PATH)
                .queryParam(FOREMAN_SEARCH_PARAM,
                  query);

        LOGGER.finer(target.toString());
        Response response = getForemanResponse(target);

        String responseAsString = response.readEntity(String.class);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            LOGGER.finer(responseAsString);
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readValue(responseAsString, JsonNode.class);
                JsonNode hosts = json.get("results");
                if (hosts != null && hosts.isArray()) {
                    for (JsonNode host : hosts) {
                        String name = host.get("name").asText();
                        HostInfo info = getHostInfo(name);
                        if (info == null) throw new CommunicationError("Unable to fetch details for found host: " + name);
                        hostsMap.put(info.getName(), info);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unhandled exception getting compatible hosts: ", e);
            }
        } else {
            LOGGER.log(Level.SEVERE, "Unable to get compatible hosts. HTTP status: " + response.getStatus() + "\n" + responseAsString);
        }
        return Collections.unmodifiableMap(hostsMap);
    }

    /**
     * Get list of compatible hosts.
     *
     * @return list of host names.
     * @throws Exception if occurs.
     */
    @Nonnull
    Map<String, HostInfo> getCompatibleHosts() throws Exception {
        String query = "has " + FOREMAN_SEARCH_LABELPARAM
                + " and has " + FOREMAN_SEARCH_RESERVEDPARAM
                + " and has " + FOREMAN_REMOTEFS_ROOT;
        return getHostsForQuery(query);
    }

    /**
     * Get the list of all already reserved hosts from Foreman for this Jenkins.
     *
     * @return list of all reserved hosts.
     * @throws Exception if occurs.
     */
    @Nonnull
    public List<String> getAllReservedHosts() throws Exception {
        final List<String> hostsList = new ArrayList<String>();

        WebTarget target = base.path(FOREMAN_SHOW_RESERVED_PATH);

        LOGGER.finer(target.toString());
        Response response = getForemanResponse(target);
        Response.Status status = Response.Status.fromStatusCode(response.getStatus());

        if (status == Response.Status.OK) {
            String responseAsString = response.readEntity(String.class);
            LOGGER.finer(responseAsString);
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode hosts = mapper.readValue(responseAsString, JsonNode.class);
                if (hosts != null && hosts.isArray()) {

                    // Extract only our reserved hosts
                    for (JsonNode host : hosts) {
                        JsonNode hostParams = host.get("host_parameters");
                        if (hostParams != null && hostParams.isArray()) {
                            for (JsonNode hostParam : hostParams) {
                                if (hostParam.get("name").textValue().compareTo("RESERVED") == 0
                                        && hostParam.get("value").asText().compareTo(getReserveReason()) == 0) {
                                    hostsList.add(host.get("name").asText());
                                    break; // Not necessary to process further 'host_parameters' for this host
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unhandled exception during performing search all reserved hosts: ", e);
            }
        } else {
            // Ruby/Foreman's possible responses
            // (See https://github.com/david-caro/foreman_reserve/blob/master/app/controllers/api/v2/reserves_controller.rb#L88)
            if (status == Response.Status.NOT_FOUND) {
                return hostsList;
            }
            String err = "Unexpected failure during retrieving all reserved hosts, returned code: "
                    + response.getStatus();
            Exception e = new Exception(err);
            LOGGER.log(Level.SEVERE, err, e);
            throw e;
        }

        return hostsList;
    }

    /**
     * Action or query performed by the library has failed.
     *
     * Dedicated subclasses should be thrown.
     */
    public static abstract class ActionFailed extends RuntimeException {
        public ActionFailed(String message) {
            super(message);
        }

        public ActionFailed(String message, Throwable cause) {
            super(message, cause);
        }

        public ActionFailed(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Problem while talking to Foreman.
     *
     * Network problem, service malfunction or failure performing an action.
     */
    public static class CommunicationError extends ActionFailed {
        public CommunicationError(String message) {
            super(message);
        }

        public CommunicationError(String message, Throwable cause) {
            super(message, cause);
        }

        public CommunicationError(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Library does not comprehend Foreman reply.
     *
     * The response format and the library are not compatible.
     */
    public static class ProtocolMismatch extends ActionFailed {
        public ProtocolMismatch(String message) {
            super(message);
        }

        public ProtocolMismatch(String message, Throwable cause) {
            super(message, cause);
        }

        public ProtocolMismatch(Throwable cause) {
            super(cause);
        }
    }
}

package com.redhat.foreman.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Architecture;
import com.redhat.foreman.cli.model.Domain;
import com.redhat.foreman.cli.model.Environment;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Hostgroup;
import com.redhat.foreman.cli.model.Medium;
import com.redhat.foreman.cli.model.OperatingSystem;
import com.redhat.foreman.cli.model.PTable;
import com.redhat.foreman.cli.model.Parameter;
import com.redhat.foreman.cli.model.Reservation;
import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 06/01/17.
 */
public class Api {
    private static final String V2 = "/v2";
    private String server;
    private String user;
    private String password;
    private WebTarget base;
    private Logger LOGGER = Logger.getLogger(Api.class);

    public Api(String server, String user, String password) {
        this.server = server;
        this.user = user;
        this.password = password;
        initClient();
    }

    private void initClient() {
        ClientConfig clientConfig = new ClientConfig();
        HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, password);
        clientConfig.register(feature);
        Client client = ClientBuilder.newClient(clientConfig);
        String s = server;
        //remove trailing slash
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        //remove api
        if (s.endsWith("v2")) {
            s = s.substring(0, s.length() - 2);
        }
        base = client
                .target(s);
    }

    public Domain createDomain(String name) throws ForemanApiException {
        Domain domain = getDomain(name);
        if (domain != null) {
            LOGGER.info("Domain " + name + " already exists...");
            return domain;
        }
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("domain", innerObject);
        String json = jsonObject.toString();

        return (Domain)createObject("domains", Domain.class, json);
    }

    private Object createObject(String objectType, Class className, String json) throws ForemanApiException {
        Response response =
                base.path(V2 + "/" + objectType).request(MediaType.APPLICATION_JSON)
                        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
        String responseAsString = response.readEntity(String.class);
        LOGGER.debug(responseAsString);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.CREATED ||
                Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK  ) {
            Gson gson = new Gson();
            return gson.fromJson(responseAsString, className.getClass());
        } else {
            throw new ForemanApiException("Creating " + objectType
                    + " returned code " + response.getStatus() + ".", responseAsString);
        }
    }

    public Domain getDomain(String name) {
        Response response = base.path(V2 + "/domains")
                .queryParam("search", "name = " + name)
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "domains");
        Gson gson = new Gson();
        return gson.fromJson(result, Domain.class);
    }

    public Environment getEnvironment(String name) {
        Response response = base.path(V2 + "/environments")
                .queryParam("search", "name = " + name)
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "environments");
        Gson gson = new Gson();
        return gson.fromJson(result, Environment.class);
    }

    private String getResultString(Response response,
                                   String objectType) {
        return getResultString(response, objectType, true);

    }

    private String getResultString(Response response,
                                         String objectType, boolean firstElementOnly) {
        String responseAsString = response.readEntity(String.class);
        LOGGER.debug(responseAsString);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readValue(responseAsString, JsonNode.class);
                JsonNode results = json.get("results");
                if (results != null && results.isArray()) {
                    if (firstElementOnly) {
                        JsonNode firstElem = results.get(0);
                        if (firstElem == null) {
                            return null;
                        }
                        return firstElem.toString();
                    } else {
                        return results.toString();
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Unhandled exception getting object " + objectType + ": ", e);
                e.printStackTrace();
            }
        } else {
            LOGGER.error("Retrieving " + objectType
                    + " returned code " + response.getStatus() + ".");
        }
        return null;
    }

    public Environment createEnvironment(String name) throws ForemanApiException {
        Environment env = getEnvironment(name);
        if (env != null) {
            LOGGER.info("Environment " + name + " already exists...");
            return env;
        }
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("environment", innerObject);
        String json = jsonObject.toString();

        return (Environment)createObject("environments", Environment.class, json);
    }

    public Architecture getArchitecture(String name) {
        Response response = base.path(V2 + "/architectures")
                .queryParam("search", "name = " + name)
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "architectures");
        Gson gson = new Gson();
        return gson.fromJson(result, Architecture.class);
    }

    public Medium getMedium(String name) {
        Response response = base.path(V2 + "/media")
                .queryParam("search", "name = " + "\"" + name + "\"")
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "media");
        Gson gson = new Gson();
        return gson.fromJson(result, Medium.class);
    }

    public PTable getPTable(String name) {
        Response response = base.path(V2 + "/ptables")
                .queryParam("search", "name = " + "\"" + name + "\"")
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "ptables");
        Gson gson = new Gson();
        return gson.fromJson(result, PTable.class);
    }

    public OperatingSystem getOperatingSystem(String name) {
        Response response = base.path(V2 + "/operatingsystems")
                .queryParam("search", "name = " + "\"" + name + "\"")
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "operatingsystems");
        Gson gson = new Gson();
        return gson.fromJson(result, OperatingSystem.class);
    }

    public Hostgroup getHostGroup(String name) {
        Response response = base.path(V2 + "/hostgroups")
                .queryParam("search", "name = " + "\"" + name + "\"")
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "hostgroups");
        Gson gson = new Gson();
        return gson.fromJson(result, Hostgroup.class);
    }

    public String getVersion() {
        Response response = base.path(V2 + "/status")
                .request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.debug(responseAsString);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK) {
            JsonObject jobj = new Gson().fromJson(responseAsString, JsonObject.class);
            return jobj.get("version").getAsString();
        }
        return null;
    }

    public Host getHost(String name) {
        Response response = base.path(V2 + "/hosts/" + name)
                .request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.debug(responseAsString);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK  ) {
            Gson gson = new Gson();
            return gson.fromJson(responseAsString, Host.class);
        }
        return null;
    }

    public OperatingSystem createOperatingSystem(String name,
                                                 String major,
                                                 String minor,
                                                 int arch_id,
                                                 int media_id,
                                                 int ptable_id,
                                                 String family) throws ForemanApiException {
        OperatingSystem os = getOperatingSystem(name);
        if (os != null) {
            LOGGER.info("OperatingSystem " + name + " already exists...");
            return os;
        }
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);
        innerObject.addProperty("major", major);
        innerObject.addProperty("minor", minor);
        innerObject.addProperty("architecture_ids", arch_id);
        innerObject.addProperty("medium_ids", media_id);
        innerObject.addProperty("ptable_ids", ptable_id);
        innerObject.addProperty("family", family);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("operatingsystem", innerObject);
        String json = jsonObject.toString();

        return (OperatingSystem)createObject("operatingsystems", OperatingSystem.class, json);
    }

    public Hostgroup createHostGroup(String name,
                                     int envId,
                                     int domainID,
                                     int archId,
                                     int osId,
                                     int mediaId,
                                     int ptableId,
                                     String rootPass) throws ForemanApiException {
        Hostgroup hg = getHostGroup(name);
        if (hg != null) {
            LOGGER.info("Hostgroup " + name + " already exists...");
            return hg;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);
        innerObject.addProperty("environment_id", envId);
        innerObject.addProperty("domain_id", domainID);
        innerObject.addProperty("architecture_id", archId);
        innerObject.addProperty("operatingsystem_id", osId);
        innerObject.addProperty("medium_id", mediaId);
        innerObject.addProperty("ptable_id", ptableId);
        innerObject.addProperty("root_pass", rootPass);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("hostgroup", innerObject);
        String json = jsonObject.toString();

        return (Hostgroup)createObject("hostgroups", Hostgroup.class, json);
    }

    public Hostgroup createHostGroup(String name) throws ForemanApiException {
        Hostgroup hg = getHostGroup(name);
        if (hg != null) {
            LOGGER.info("Hostgroup " + name + " already exists...");
            return hg;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("hostgroup", innerObject);
        String json = jsonObject.toString();

        return (Hostgroup)createObject("hostgroups", Hostgroup.class, json);
    }

    public Host createHost(String name,
                           String ip,
                           Domain domain,
                           int hostgroupid,
                           int archId,
                           int osId,
                           int mediaId,
                           int ptableId,
                           int envId,
                           String rootPass,
                           String macAddress) throws ForemanApiException {
        Host host = getHost(name + "." + domain.getName());
        if (host != null) {
            LOGGER.info("Host " + name + " already exists...");
            return host;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name + "." + domain.getName());
        innerObject.addProperty("ip", ip);
        innerObject.addProperty("environment_id", envId);
        innerObject.addProperty("domain_id", domain.id);
        innerObject.addProperty("hostgroup_id", hostgroupid);
        innerObject.addProperty("architecture_id", archId);
        innerObject.addProperty("operatingsystem_id", osId);
        innerObject.addProperty("medium_id", mediaId);
        innerObject.addProperty("ptable_id", ptableId);
        innerObject.addProperty("root_pass", rootPass);
        innerObject.addProperty("mac", macAddress);
        innerObject.addProperty("managed", false);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        return (Host)createObject("hosts", Host.class, json);
    }

    public Host addHostParameter(Host host, Parameter parameter) throws ForemanApiException {
        JsonArray params = new JsonArray();
        JsonObject paramObject = new JsonObject();
        paramObject.addProperty("name", parameter.getName());
        paramObject.addProperty("value", parameter.getValue());
        params.add(paramObject);

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", host.getName());
        innerObject.add("host_parameters_attributes", params);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        Response response =
                base.path(V2 + "/hosts/" + host.id).request(MediaType.APPLICATION_JSON)
                        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
        String responseAsString = response.readEntity(String.class);
        LOGGER.debug(responseAsString);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.CREATED ||
                Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK  ) {
            Gson gson = new Gson();
            return gson.fromJson(responseAsString, Host.class);
        }
        throw new ForemanApiException("Adding host parameter returned code " + response.getStatus()
                + ".", responseAsString);
    }

    public Parameter updateHostParameter(Host host, Parameter parameter) throws ForemanApiException {

        Parameter existing = getHostParameter(host, parameter.getName());
        if (existing == null) {
            Host hostAddedParam = addHostParameter(host, parameter);
            return hostAddedParam.getParameterValue(parameter.getName());
        }
        if (existing.getValue().equals(parameter.getValue())) {
            LOGGER.info("Value for parameter " + parameter.getName() + " already set to " + parameter.getValue());
            return existing;
        }
        parameter.id = existing.id;


        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", parameter.getName());
        innerObject.addProperty("value", parameter.getValue());

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("parameter", innerObject);
        String json = jsonObject.toString();

        Response response =
                base.path(V2 + "/hosts/" + host.id + "/parameters/"
                        + parameter.id).request(MediaType.APPLICATION_JSON)
                        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
        String responseAsString = response.readEntity(String.class);
        LOGGER.debug(responseAsString);
        if (Response.Status.fromStatusCode(response.getStatus()) == Response.Status.CREATED ||
                Response.Status.fromStatusCode(response.getStatus()) == Response.Status.OK  ) {
            Gson gson = new Gson();
            return gson.fromJson(responseAsString, Parameter.class);
        }
        throw new ForemanApiException("Updating host parameter returned code " + response.getStatus()
                    + ".", responseAsString);
    }

    public Parameter getHostParameter(Host host, String parameterName) {
        Response response = base.path(V2 + "/hosts/" + host.getName())
                .request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.debug(responseAsString);
        Gson gson = new Gson();
        Host updatedHost = gson.fromJson(responseAsString, Host.class);
        return updatedHost.getParameterValue(parameterName);
    }

    public List<Host> getHosts(Hostgroup hostgroup) {
        Type listType = new TypeToken<ArrayList<Host>>(){}.getType();
        Response response = base.path(V2 + "/hostgroups/" + hostgroup.getName() + "/hosts")
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "hosts", false);
        Gson gson = new Gson();
        return gson.fromJson(result, listType);
    }

    public List<Host> getHosts(Environment environment) {
        Type listType = new TypeToken<ArrayList<Host>>(){}.getType();
        Response response = base.path(V2 + "/environments/" + environment.getName() + "/hosts")
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "hosts", false);
        Gson gson = new Gson();
        return gson.fromJson(result, listType);
    }

    public List<Host> getHosts() {
        Type listType = new TypeToken<ArrayList<Host>>(){}.getType();
        Response response = base.path(V2 + "/hosts")
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "hosts", false);
        Gson gson = new Gson();
        return gson.fromJson(result, listType);
    }

    public List<Host> getHosts(String query) {
        Type listType = new TypeToken<ArrayList<Host>>(){}.getType();
        Response response = base.path(V2 + "/hosts")
                .queryParam("search", query)
                .request(MediaType.APPLICATION_JSON).get();
        String result = getResultString(response, "hosts", false);
        Gson gson = new Gson();
        return gson.fromJson(result, listType);
    }

    public static String fixValue(Parameter param) {
        String val = "";
        if (param != null) {
            val = param.getValue();
        }
        return val;
    }

    public static Parameter fixParameterValue(Parameter param) {
        if (param != null && param.getValue() == null) {
            param.setValue("");
        }
        return param;
    }
    public String releaseHost(Host h) {
        Response response = base.path("/hosts_release")
                .queryParam("query", "name = " + h.getName())
                .request(MediaType.APPLICATION_JSON).get();
        String responseAsString = response.readEntity(String.class);
        LOGGER.info(responseAsString);
        return responseAsString;
    }

    public Reservation getHostReservation(Host h) {
        String reservation = fixValue(this.getHostParameter(h, "RESERVED"));
        if (reservation.equals("false")) {
            return Reservation.none();
        } else {
            return new Reservation(reservation);
        }
    }

    public String reserveHost(Host h, String reserveReason) {
        String reservation = fixValue(this.getHostParameter(h, "RESERVED"));
        if (reservation.equals("false")) {
            Response response = base.path("/hosts_reserve")
                    .queryParam("query", "name = \"" + h.getName() + "\"")
                    .queryParam("reason", reserveReason)
                    .request(MediaType.APPLICATION_JSON).get();
            String responseAsString = response.readEntity(String.class);
            LOGGER.debug(response.getStatus());
            LOGGER.debug(responseAsString);
            return responseAsString;
        } else {
            LOGGER.error("Already RESERVED by: " + reservation);
            return "Already RESERVED by: " + reservation;
        }
    }

    public OperatingSystem createOperatingSystem(String name, String major, String minor, int arch_id) throws ForemanApiException {
        OperatingSystem os = getOperatingSystem(name);
        if (os != null) {
            LOGGER.info("OperatingSystem " + name + " already exists...");
            return os;
        }
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);
        innerObject.addProperty("major", major);
        innerObject.addProperty("minor", minor);
        innerObject.addProperty("architecture_ids", arch_id);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("operatingsystem", innerObject);
        String json = jsonObject.toString();

        return (OperatingSystem)createObject("operatingsystems", OperatingSystem.class, json);
    }

    public OperatingSystem createOperatingSystem(String name, String major, String minor) throws ForemanApiException {
        OperatingSystem os = getOperatingSystem(name);
        if (os != null) {
            LOGGER.info("OperatingSystem " + name + " already exists...");
            return os;
        }
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);
        innerObject.addProperty("major", major);
        innerObject.addProperty("minor", minor);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("operatingsystem", innerObject);
        String json = jsonObject.toString();

        return (OperatingSystem)createObject("operatingsystems", OperatingSystem.class, json);
    }

    public Host createHost(String name, String ip, Domain domain, int archId, int osId, int envId) throws ForemanApiException {
        Host host = getHost(name + "." + domain.getName());
        if (host != null) {
            LOGGER.info("Host " + name + " already exists...");
            return host;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name + "." + domain.getName());
        innerObject.addProperty("ip", ip);
        innerObject.addProperty("environment_id", envId);
        innerObject.addProperty("domain_id", domain.id);
        innerObject.addProperty("architecture_id", archId);
        innerObject.addProperty("operatingsystem_id", osId);
        innerObject.addProperty("managed", false);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        return (Host)createObject("hosts", Host.class, json);
    }

    public Host createHost(String name, String ip, Domain domain, int hostgroup_id, int envId) throws ForemanApiException {
        Host host = getHost(name + "." + domain.getName());
        if (host != null) {
            LOGGER.info("Host " + name + " already exists...");
            return host;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name + "." + domain.getName());
        innerObject.addProperty("ip", ip);
        innerObject.addProperty("domain_id", domain.id);
        innerObject.addProperty("hostgroup_id", hostgroup_id);
        innerObject.addProperty("environment_id", envId);
        innerObject.addProperty("managed", false);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        return (Host)createObject("hosts", Host.class, json);
    }

    public Host createHost(String name, String ip, Domain domain, int osId) throws ForemanApiException {
        Host host = getHost(name + "." + domain.getName());
        if (host != null) {
            LOGGER.info("Host " + name + " already exists...");
            return host;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name + "." + domain.getName());
        innerObject.addProperty("ip", ip);
        innerObject.addProperty("domain_id", domain.id);
        innerObject.addProperty("operatingsystem_id", osId);
        innerObject.addProperty("managed", false);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        return (Host)createObject("hosts", Host.class, json);
    }

    public Host createHost(String name, String ip, Domain domain) throws ForemanApiException {
        Host host = getHost(name + "." + domain.getName());
        if (host != null) {
            LOGGER.info("Host " + name + " already exists...");
            return host;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name + "." + domain.getName());
        innerObject.addProperty("ip", ip);
        innerObject.addProperty("domain_id", domain.id);
        innerObject.addProperty("managed", false);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        return (Host)createObject("hosts", Host.class, json);
    }

    public boolean isReserved(Host host) {
        Reservation reservation = getHostReservation(host);
        if (reservation instanceof Reservation.EmptyReservation) {
            return false;
        }
        return true;
    }

    public Host createHost(String name, Domain domain) throws ForemanApiException {
        Host host = getHost(name + "." + domain.getName());
        if (host != null) {
            LOGGER.info("Host " + name + " already exists...");
            return host;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name + "." + domain.getName());
        innerObject.addProperty("domain_id", domain.id);
        innerObject.addProperty("managed", false);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        return (Host)createObject("hosts", Host.class, json);
    }

    public Host createHost(String name) throws ForemanApiException {
        Host host = getHost(name);
        if (host != null) {
            LOGGER.info("Host " + name + " already exists...");
            return host;
        }

        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("name", name);
        innerObject.addProperty("managed", false);

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("host", innerObject);
        String json = jsonObject.toString();

        return (Host)createObject("hosts", Host.class, json);
    }
}

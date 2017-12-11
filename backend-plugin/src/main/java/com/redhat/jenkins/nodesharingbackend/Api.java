/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharingbackend;

import com.redhat.jenkins.nodesharing.ActionFailed;
import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import com.redhat.jenkins.nodesharing.transport.DiscoverRequest;
import com.redhat.jenkins.nodesharing.transport.DiscoverResponse;
import com.redhat.jenkins.nodesharing.transport.Entity;
import com.redhat.jenkins.nodesharing.transport.NodeStatusRequest;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharing.transport.ReturnNodeRequest;
import com.redhat.jenkins.nodesharing.transport.RunStatusRequest;
import com.redhat.jenkins.nodesharing.transport.RunStatusResponse;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Receive and send REST commands from/to executor Jenkinses.
 */
@Extension
@Restricted(NoExternalUse.class)
// TODO Check permission
// TODO Fail fast if there is no ConfigRepo.Snapshot - Broken orchestrator
public class Api implements RootAction {

    private static final Logger LOGGER = Logger.getLogger(Api.class.getName());

    private static final String HIDDEN = null;

    private Properties properties = null;
    private static final String PROPERTY_VERSION = "version";

    private static Client webClient = null;

    public static @Nonnull Api getInstance() {
        ExtensionList<Api> list = Jenkins.getInstance().getExtensionList(Api.class);
        assert list.size() == 1;
        return list.iterator().next();
    }

    @Override public String getIconFileName() {
        return HIDDEN;
    }

    @Override public String getDisplayName() {
        return HIDDEN;
    }

    @Override public String getUrlName() {
        return "node-sharing-orchestrator";
    }

    /**
     * Get properties.
     *
     * @return Properties.
     */
    @Nonnull
    private Properties getProperties() {
        if(properties == null) {
            properties = new Properties();
            try {
                properties.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));
            } catch (IOException e) {
                LOGGER.severe("Cannot load properties from ");
                properties = new Properties();
            }
        }
        return properties;
    }

    @Nonnull
    private WebTarget getWebClient(@Nonnull final String url) {
        if (webClient == null) {
            ClientConfig clientConfig = new ClientConfig();
            clientConfig.register(JacksonFeature.class);
            webClient = ClientBuilder.newClient(clientConfig);

            // TODO HTTP autentization
            //HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, Secret.toString(password));
            //clientConfig.register(feature);

            // Define a quite defensive timeouts
            webClient.property(ClientProperties.CONNECT_TIMEOUT, 60000);   // 60s
            webClient.property(ClientProperties.READ_TIMEOUT,    300000);  // 5m
        }

        return webClient.target(url);
    }

    /**
     * Do POST HTTP request on target.
     *
     * @param target The request.
     * @param entity JSON string.
     *
     * @return Response from the server.
     */
    @Nonnull
    private Response doPostRequest(@Nonnull final WebTarget target, @Nonnull final Object entity) {
        return doPostRequest(target, entity, Response.Status.OK);

    }

    /**
     * Do POST HTTP request on target and throws exception if response doesn't match the expectation.
     *
     * @param target The request.
     * @param entity POSTed entity.
     * @param status Expected status.
     *
     * @return Response from the server.
     */
    @Nonnull
    private Response doPostRequest(@Nonnull final WebTarget target, @Nonnull final Object entity,
                                   @Nonnull final Response.Status status) {
        Response response = target.queryParam(PROPERTY_VERSION, getProperties().getProperty(PROPERTY_VERSION, ""))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(javax.ws.rs.client.Entity.json(entity));
        if (!status.equals(Response.Status.fromStatusCode(response.getStatus()))) {
            throw new ActionFailed.CommunicationError("Performing POST request '" + target.toString()
                    + "' returns unexpected response status '" + response.getStatus()
                    + "' [" + response.readEntity(String.class) + "]");
        }
        return response;
    }

    //// Outgoing

    /**
     * Signal to Executor Jenkins to start using particular node.
     *
     * @param owner Jenkins instance the node is reserved for.
     * @param node Node to be reserved.
     */
    public void utilizeNode(@Nonnull ExecutorJenkins owner, @Nonnull SharedNode node) {

    }

    /**
     * Query executor Jenkins to report shared hosts it uses.
     *
     * It should be the Orchestrator who has an authority to say that but this is to query executor's view of things.
     * Most useful when Orchestrator boots after crash with all the reservation info possibly lost or outdated.
     *
     * @param owner Jenkins instance to query.
     * @return List of host names the instance is using.
     */
    public @Nonnull Collection<String> reportUsage(@Nonnull ExecutorJenkins owner) {
        return null;
    }

    /**
     * Determine whether the host is still used by executor.
     *
     * Ideally, the host is utilized between {@link #utilizeNode(ExecutorJenkins, SharedNode)} was send and
     * {@link #doReturnNode(StaplerRequest, StaplerResponse)} was received but in case of any of the requests failed to be delivered for some
     * reason, there is this way to recheck. Note this has to recognise Jenkins was stopped or plugin was uninstalled so
     * we can not rely on node-sharing API on Executor end.
     *
     * @param owner Jenkins instance to query.
     * @param node The node to query.
     * @return true if the computer is still connected there, false if we know it is not, null otherwise.
     */
    public Boolean isUtilized(@Nonnull ExecutorJenkins owner, @Nonnull SharedNode node) {
        return true;
    }

    //// Incoming

    /**
     * Dummy request to test the connection/compatibility.
     */
    public void doDiscover(StaplerRequest req, StaplerResponse rsp) throws IOException {
        DiscoverRequest request = Entity.fromInputStream(req.getInputStream(), DiscoverRequest.class);
        Pool pool = Pool.getInstance();
        String version = getProperties().getProperty("version", "");

        // Sanity checking
        StringBuilder diagnosisBuilder = new StringBuilder();
        if (!request.getVersion().equals(version)) {
            diagnosisBuilder.append("Orchestrator plugin version is ")
                    .append(request.getVersion())
                    .append(" but executor uses ")
                    .append(version)
                    .append(". ")
            ;
        }
        String configEndpoint = pool.getConfigEndpoint();
        if (!request.getConfigRepoUrl().equals(configEndpoint)) {
            diagnosisBuilder.append("Orchestrator is configured from ")
                    .append(request.getConfigRepoUrl())
                    .append(" but executor uses ")
                    .append(configEndpoint)
                    .append(". ")
            ;
        }

        String diagnosis = diagnosisBuilder.toString();
        DiscoverResponse response = new DiscoverResponse(
                configEndpoint, version, diagnosis, pool.getConfig().getNodes().values()
        );

        rsp.setContentType("application/json");
        response.toOutputStream(rsp.getOutputStream());
    }

    /**
     * Report workload to be executed on orchestrator for particular executor master.
     */
    @RequirePOST
    public void doReportWorkload(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        ReportWorkloadRequest request = Entity.fromInputStream(req.getInputStream(), ReportWorkloadRequest.class);

        System.out.println("doReportWorkload(): Backend got this request: ");
        for (ReportWorkloadRequest.Workload.WorkloadItem item : request.getWorkload().getItems()) {
            System.out.println("Id: " + item.getId() + ", Name: '" + item.getName() + "'");
        }
        // TODO Process the workload
        // TODO In set of queue items to be build
        // TODO Out nothing or OK
    }

    /**
     * Return node to orchestrator when no longer needed.
     */
    @RequirePOST
    public void doReturnNode(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        ReturnNodeRequest request = Entity.fromInputStream(req.getInputStream(), ReturnNodeRequest.class);
        Computer c = Jenkins.getActiveInstance().getComputer(request.getNodeName());
        if (!(c instanceof SharedComputer)) {
            // TODO computer not reservable
            return;
        }
        SharedComputer computer = (SharedComputer) c;
        ReservationTask.ReservationExecutable executable = computer.getReservation();
        if (executable == null) {
            // TODO computer not reserved
            return;
        }
        // TODO The owner parameter is in no way sufficient proof the client is authorized to release this
        executable.complete(request.getExecutorName());
        // TODO Report status
    }

    @Nonnull
    public NodeStatusResponse.Status nodeStatus(@Nonnull final ExecutorJenkins jenkins, @Nonnull final String nodeName) {
        final String version = getProperties().getProperty("version", "");

        WebTarget target = getWebClient(jenkins.getEndpointUrl().toString())
                .path("cloud/" + jenkins.getName() + "/api" + NodeStatusRequest.REQUEST_URI);
        NodeStatusRequest request = new NodeStatusRequest(
                Pool.getInstance().getConfigEndpoint(),
                version,
                nodeName
        );
        NodeStatusResponse response = Entity.fromInputStream(
                (InputStream) doPostRequest(target, request).getEntity(),
                NodeStatusResponse.class
        );

        return response.getStatus();
    }

    @Nonnull
    public RunStatusResponse.Status runStatus(@Nonnull final ExecutorJenkins jenkins, @Nonnull final long id) {
        final String version = getProperties().getProperty("version", "");

        WebTarget target = getWebClient(jenkins.getEndpointUrl().toString())
                .path("cloud/" + jenkins.getName() + "/api" + RunStatusRequest.REQUEST_URI);
        RunStatusRequest request = new RunStatusRequest(
                Pool.getInstance().getConfigEndpoint(),
                version,
                id
        );
        RunStatusResponse response = Entity.fromInputStream(
                (InputStream) doPostRequest(target, request).getEntity(),
                RunStatusResponse.class
        );

        return response.getStatus();
    }
}

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
import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import com.redhat.jenkins.nodesharing.NodeDefinition;
import com.redhat.jenkins.nodesharing.RestEndpoint;
import com.redhat.jenkins.nodesharing.transport.DiscoverRequest;
import com.redhat.jenkins.nodesharing.transport.DiscoverResponse;
import com.redhat.jenkins.nodesharing.transport.Entity;
import com.redhat.jenkins.nodesharing.transport.NodeStatusRequest;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import com.redhat.jenkins.nodesharing.transport.ReportUsageRequest;
import com.redhat.jenkins.nodesharing.transport.ReportUsageResponse;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadResponse;
import com.redhat.jenkins.nodesharing.transport.ReturnNodeRequest;
import com.redhat.jenkins.nodesharing.transport.UtilizeNodeRequest;
import com.redhat.jenkins.nodesharing.transport.UtilizeNodeResponse;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Queue;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.apache.http.HttpStatus;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Receive and send REST commands from/to executor Jenkinses.
 */
@Extension
@Restricted(NoExternalUse.class)
public class Api implements RootAction {

    private static final Logger LOGGER = Logger.getLogger(Api.class.getName());

    private static final String HIDDEN = null;

    private final @Nonnull String version;

    public Api() {
        try {
            // TODO getClass().getPackage().getImplementationVersion() might work equally well
            // PJ: Not working, during JUnit phase execution there aren't made packages...
            InputStream resource = this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties");
            if (resource == null) {
                version = Jenkins.getActiveInstance().pluginManager.whichPlugin(getClass()).getVersion();
            } else {
                Properties properties = new Properties();
                properties.load(resource);
                version = properties.getProperty("version");
            }
            if (version == null) throw new AssertionError("No version in assembly properties");
        } catch (IOException e) {
            throw new AssertionError("Cannot load assembly properties", e);
        }
    }

    public static @Nonnull Api getInstance() {
        ExtensionList<Api> list = Jenkins.getActiveInstance().getExtensionList(Api.class);
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

    //// Outgoing

    /**
     * Signal to Executor Jenkins to start using particular node.
     *
     * @param executor Jenkins instance the node is reserved for.
     * @param node Node to be reserved.
     * @return true is the client accepted the node, false otherwise.
     */
    public boolean utilizeNode(@Nonnull ExecutorJenkins executor, @Nonnull ShareableNode node) {
        Pool pool = Pool.getInstance();
        String configRepoUrl = pool.getConfigRepoUrl();
        UtilizeNodeRequest request = new UtilizeNodeRequest(configRepoUrl, version, node.getNodeDefinition());
        RestEndpoint rest = executor.getRest(configRepoUrl, pool.getCredential());
        try {
            rest.executeRequest(rest.post("utilizeNode"), request, UtilizeNodeResponse.class);
            return true;
        } catch (ActionFailed.RequestFailed ex) {
            if (ex.getStatusCode() == HttpStatus.SC_GONE) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * Query executor Jenkins to report shared hosts it uses.
     *
     * It should be the Orchestrator who has an authority to say that but this is to query executor's view of things.
     * Most useful when Orchestrator boots after crash with all the reservation info possibly lost or outdated.
     *
     * @param owner Jenkins instance to query.
     */
    public @Nonnull ReportUsageResponse reportUsage(@Nonnull ExecutorJenkins owner) {
        Pool pool = Pool.getInstance();
        String configRepoUrl = pool.getConfigRepoUrl();
        ReportUsageRequest request = new ReportUsageRequest(configRepoUrl, version);
        RestEndpoint rest = owner.getRest(configRepoUrl, pool.getCredential());
        return rest.executeRequest(rest.post("reportUsage"), request, ReportUsageResponse.class);
    }

    /**
     * Determine whether the host is still used by particular executor.
     *
     * Ideally, the host is utilized between {@link #utilizeNode(ExecutorJenkins, ShareableNode)} was send and
     * {@link #doReturnNode(StaplerRequest, StaplerResponse)} was received but in case of any of the requests failed to
     * be delivered for some reason, there is this way to recheck. Note this has to recognise Jenkins was stopped or
     * plugin was uninstalled so we can not rely on node-sharing API on Executor end.
     *
     * @param owner Jenkins instance to query.
     * @param node The node to query.
     * @return true if the computer is still connected there, false if we know it is not, null otherwise.
     */
    public Boolean isUtilized(@Nonnull ExecutorJenkins owner, @Nonnull ShareableNode node) {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    public NodeStatusResponse.Status nodeStatus(@Nonnull final ExecutorJenkins jenkins, @Nonnull final String nodeName) {
        Pool pool = Pool.getInstance();
        String configRepoUrl = pool.getConfigRepoUrl();
        NodeStatusRequest request = new NodeStatusRequest(configRepoUrl, version, nodeName);
        RestEndpoint rest = jenkins.getRest(configRepoUrl, pool.getCredential());
        NodeStatusResponse nodeStatus = rest.executeRequest(rest.post("nodeStatus"), request, NodeStatusResponse.class);
        return nodeStatus.getStatus();
    }

//    @Nonnull
//    public RunStatusResponse.Status runStatus(@Nonnull final ExecutorJenkins jenkins, @Nonnull final long id) {
//        RunStatusRequest request = new RunStatusRequest(
//                Pool.getInstance().getConfigEndpoint(),
//                getProperties().getProperty("version", ""),
//                id
//        );
//        RestEndpoint rest = jenkins.getRest();
//        RunStatusResponse response = rest.executeRequest(rest.post("runStatus"), RunStatusResponse.class, request);
//        return response.getStatus();
//    }

    //// Incoming

    /**
     * Initial request to test the connection/compatibility.
     */
    @RequirePOST
    public void doDiscover(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);

        Pool pool = Pool.getInstance();
        Collection<NodeDefinition> nodes = pool.getConfig().getNodes().values(); // Fail early when there is no config

        DiscoverRequest request = Entity.fromInputStream(req.getInputStream(), DiscoverRequest.class);

        String version = this.version;

        // Sanity checking
        StringBuilder diagnosisBuilder = new StringBuilder();
        if (!request.getVersion().equals(version)) {
            diagnosisBuilder.append("Orchestrator plugin version is ")
                    .append(version)
                    .append(" but executor uses ")
                    .append(request.getVersion())
                    .append(". ")
            ;
        }
        String configEndpoint = pool.getConfigRepoUrl();
        if (!request.getConfigRepoUrl().equals(configEndpoint)) {
            diagnosisBuilder.append("Orchestrator is configured from ")
                    .append(configEndpoint)
                    .append(" but executor uses ")
                    .append(request.getConfigRepoUrl())
                    .append(". ")
            ;
        }

        String diagnosis = diagnosisBuilder.toString();
        DiscoverResponse response = new DiscoverResponse(configEndpoint, version, diagnosis, nodes);

        rsp.setContentType("application/json");
        response.toOutputStream(rsp.getOutputStream());
    }

    /**
     * Report workload to be executed on orchestrator for particular executor master.
     *
     * The order of items from orchestrator is preserved though not guaranteed to be exactly the same as the builds ware
     * scheduled on individual executor Jenkinses.
     */
    @RequirePOST
    public void doReportWorkload(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);

        Pool pool = Pool.getInstance();
        final ConfigRepo.Snapshot config = pool.getConfig(); // Fail early when there is no config

        final ReportWorkloadRequest request = Entity.fromInputStream(req.getInputStream(), ReportWorkloadRequest.class);

        final List<ReportWorkloadRequest.Workload.WorkloadItem> reportedItems = request.getWorkload().getItems();
        final ArrayList<ReservationTask> reportedTasks = new ArrayList<>(reportedItems.size());
        final ExecutorJenkins executor = config.getJenkinsByUrl(request.getExecutorUrl());
        for (ReportWorkloadRequest.Workload.WorkloadItem item : reportedItems) {
            reportedTasks.add(new ReservationTask(executor, item.getLabel(), item.getName(), item.getId()));
        }

        Queue.withLock(new Runnable() {
            @Override public void run() {
                Queue queue = Jenkins.getActiveInstance().getQueue();
                for (Queue.Item item : queue.getItems()) {
                    if (item.task instanceof ReservationTask) {
                        // Cancel items executor is no longer interested in and keep those it cares for
                        if (!reportedTasks.contains(item.task)) {
                            queue.cancel(item);
                        }
                        reportedTasks.remove(item.task);
                    }
                }

                // These might have been reported just before the build started the execution on Executor so now the
                // ReservationTask might be executing or even completed on executor, though there is no way for orchestrator
                // to know. This situation will be handled by executor rejecting the `utilizeNode` call.
                for (ReservationTask newTask : reportedTasks) {
                    queue.schedule2(newTask, 0);
                }
            }
        });

        String version = this.version;
        new ReportWorkloadResponse(pool.getConfigRepoUrl(), version).toOutputStream(rsp.getOutputStream());
    }

    /**
     * Return node to orchestrator when no longer needed.
     */
    @RequirePOST
    public void doReturnNode(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);

        String ocr = Pool.getInstance().getConfigRepoUrl(); // Fail early when there is no config
        ReturnNodeRequest request = Entity.fromInputStream(req.getInputStream(), ReturnNodeRequest.class);
        String ecr = request.getConfigRepoUrl();
        if (!Objects.equals(ocr, ecr)) {
            rsp.getWriter().println("Unable to return node - config repo mismatch " + ocr + " != " + ecr);
            rsp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        Jenkins jenkins = Jenkins.getActiveInstance();
        Computer c = jenkins.getComputer(request.getNodeName());
        if (c == null) {
            LOGGER.info(
                    "An attempt to return a node '" + request.getNodeName() + "' that does not exist by " + request.getExecutorUrl()
            );
            rsp.getWriter().println("No shareable node named '" + request.getNodeName() + "' exists");
            rsp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (!(c instanceof ShareableComputer)) {
            LOGGER.warning(
                    "An attempt to return a node '" + request.getNodeName() + "' that is not reservable by " + request.getExecutorUrl()
            );
            rsp.getWriter().println("No shareable node named '" + request.getNodeName() + "' exists");
            rsp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        ShareableComputer computer = (ShareableComputer) c;
        ReservationTask.ReservationExecutable executable = computer.getReservation();
        if (executable == null) {
            rsp.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        executable.complete();
        // TODO Report status
        rsp.setStatus(HttpServletResponse.SC_OK);
    }
}

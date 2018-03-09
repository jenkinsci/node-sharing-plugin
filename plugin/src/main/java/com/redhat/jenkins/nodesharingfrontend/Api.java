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
package com.redhat.jenkins.nodesharingfrontend;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.redhat.jenkins.nodesharing.ActionFailed;
import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.NodeDefinition;
import com.redhat.jenkins.nodesharing.RestEndpoint;
import com.redhat.jenkins.nodesharing.transport.DiscoverRequest;
import com.redhat.jenkins.nodesharing.transport.DiscoverResponse;
import com.redhat.jenkins.nodesharing.transport.Entity;
import com.redhat.jenkins.nodesharing.transport.ExecutorEntity;
import com.redhat.jenkins.nodesharing.transport.NodeStatusRequest;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import com.redhat.jenkins.nodesharing.transport.ReportUsageRequest;
import com.redhat.jenkins.nodesharing.transport.ReportUsageResponse;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadResponse;
import com.redhat.jenkins.nodesharing.transport.ReturnNodeRequest;
import com.redhat.jenkins.nodesharing.transport.UtilizeNodeRequest;
import com.redhat.jenkins.nodesharing.transport.UtilizeNodeResponse;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpPost;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Receive and send REST commands from/to Orchestrator Jenkins.
 */
@Restricted(NoExternalUse.class)
public class Api {

    private static final Logger LOGGER = Logger.getLogger(Api.class.getName());
    private final @Nonnull ExecutorEntity.Fingerprint fingerprint;

    private final SharedNodeCloud cloud;
    private final RestEndpoint rest;
    private final String version;

    public Api(@Nonnull final ConfigRepo.Snapshot snapshot,
               @Nonnull final String configRepoUrl,
               @CheckForNull final SharedNodeCloud cloud
    ) throws IllegalStateException {
        this.cloud = cloud;

        try {
            // TODO getClass().getPackage().getImplementationVersion() might work equally well
            // PJ: Not working, during JUnit phase execution there aren't made packages...
            InputStream resource = this.getClass().getClassLoader().getResourceAsStream("nodesharingfrontend.properties");
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

        this.fingerprint = new ExecutorEntity.Fingerprint(
                configRepoUrl,
                version,
                JenkinsLocationConfiguration.get().getUrl()
        );
        rest = new RestEndpoint(snapshot.getOrchestratorUrl(), "node-sharing-orchestrator", getRestCredential(cloud));
    }

    private @Nonnull UsernamePasswordCredentials getRestCredential(@Nonnull SharedNodeCloud cloud) throws IllegalStateException {
        String cid = cloud.getOrchestratorCredentialsId();
        UsernamePasswordCredentials cred = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, Jenkins.getInstance(), ACL.SYSTEM),
                CredentialsMatchers.withId(cid)
        );
        if (cred == null) throw new IllegalStateException(
                "No credential found for id = " + cid + " configured in cloud " + cloud.name
        );
        return cred;
    }

    //// Outgoing

    /**
     * Put the queue items to Orchestrator
     */
    // Response never used as there is likely nothing to report - async request candidate
    public void reportWorkload(@Nonnull final ReportWorkloadRequest.Workload workload) {
        final ReportWorkloadRequest request = new ReportWorkloadRequest(fingerprint, workload);
        rest.executeRequest(rest.post("reportWorkload"), request, ReportWorkloadResponse.class);
    }

    /**
     * Request to discover the state of the Orchestrator.
     *
     * @return Discovery result.
     */
    public DiscoverResponse discover() throws ActionFailed {
        return rest.executeRequest(
                rest.post("discover"),
                new DiscoverRequest(fingerprint), DiscoverResponse.class
        );
    }

    /**
     * Send request to return node. No response needed.
     */
    public void returnNode(@Nonnull SharedNode node) {
        Computer computer = node.toComputer();
        String offlineCause = null;
        if (computer != null && computer.getOfflineCause() != null) {
            offlineCause = computer.getOfflineCause().toString();
        }
        final ReturnNodeRequest.Status status = offlineCause == null
                ? ReturnNodeRequest.Status.OK
                : ReturnNodeRequest.Status.FAILED
        ;
        ReturnNodeRequest request = new ReturnNodeRequest(fingerprint, node.getHostName(), status, offlineCause);

        final HttpPost method = rest.post("returnNode");
        rest.executeRequest(method, request, new RestEndpoint.AbstractResponseHandler<Void>(method) {
            @Override
            protected boolean shouldFail(@Nonnull StatusLine sl) {
                return sl.getStatusCode() != 200 && sl.getStatusCode() != 404;
            }
        });
    }

    //// Incoming

    /**
     * Request to utilize reserved computer.
     *
     * Response code "200 OK" is used when the node was accepted and "410 Gone" when there is no longer the need so it
     * will not be used in any way and orchestrator can reuse it immediately.
     */
    @RequirePOST
    public void doUtilizeNode(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);

        UtilizeNodeRequest request = Entity.fromInputStream(req.getInputStream(), UtilizeNodeRequest.class);
        NodeDefinition definition = NodeDefinition.create(request.getFileName(), request.getDefinition());
        if (definition == null) throw new AssertionError("Unknown node definition: " + request.getFileName());

        // Utilize when there is some load for it
        Collection<LabelAtom> nodeLabels = definition.getLabelAtoms();
        for (Queue.Item item : Jenkins.getActiveInstance().getQueue().getItems()) {
            // Do not schedule unrestricted items here
            if (item.getAssignedLabel() != null && item.getAssignedLabel().matches(nodeLabels)) {
                LOGGER.fine("Accepted: " + definition.getDefinition());

                try {
                    Jenkins.getActiveInstance().addNode(cloud.createNode(definition));
                } catch (IllegalArgumentException e) {
                    e.printStackTrace(new PrintStream(rsp.getOutputStream()));
                    rsp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
                    return;
                }

                new UtilizeNodeResponse(fingerprint).toOutputStream(rsp.getOutputStream());
                rsp.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        }

        // Reject otherwise
        rsp.setStatus(HttpServletResponse.SC_GONE);
    }

    /**
     * Query Executor Jenkins to report the status of shared node.
     */
    @RequirePOST
    public void doNodeStatus(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);

        NodeStatusRequest request = Entity.fromInputStream(req.getInputStream(), NodeStatusRequest.class);
        String nodeName = request.getNodeName();
        NodeStatusResponse.Status status = NodeStatusResponse.Status.NOT_FOUND;
        if (nodeName != null) // TODO Why would it be null?
            status = cloud.getNodeStatus(request.getNodeName());
        NodeStatusResponse response = new NodeStatusResponse(fingerprint, request.getNodeName(), status);
        response.toOutputStream(rsp.getOutputStream());
    }

    @RequirePOST
    public void doReportUsage(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);

        ReportUsageRequest request = Entity.fromInputStream(req.getInputStream(), ReportUsageRequest.class);
        ArrayList<String> usedNodes = new ArrayList<>();
        for (Node node : Jenkins.getActiveInstance().getNodes()) {
            if (node instanceof SharedNode) {
                SharedNode sharedNode = (SharedNode) node;
                SharedNodeCloud cloud = SharedNodeCloud.getByName(sharedNode.getId().getCloudName());
                if (cloud != null) {
                    if (request.getConfigRepoUrl().equals(cloud.getConfigRepoUrl())) {
                        usedNodes.add(sharedNode.getHostName());
                    }
                }
            }
        }

        new ReportUsageResponse(fingerprint, usedNodes).toOutputStream(rsp.getOutputStream());
    }

//    /**
//     * Query Executor Jenkins to report the status of executed item.
//     */
//    @RequirePOST
//    public void doRunStatus(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
//        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);
//        RunStatusRequest request = com.redhat.jenkins.nodesharing.transport.Entity.fromInputStream(
//                req.getInputStream(), RunStatusRequest.class);
//        RunStatusResponse response = new RunStatusResponse(
//                fingerprint,
//                request.getRunId(),
//                cloud.getRunStatus(request.getRunId())
//        );
//        rsp.setContentType("application/json");
//        response.toOutputStream(rsp.getOutputStream());
//    }

    /**
     * Immediately return node to orchestrator. (Nice to have feature)
     */
    @RequirePOST
    public void doImmediatelyReturnNode() {
        Jenkins.getActiveInstance().checkPermission(RestEndpoint.INVOKE);
        throw new UnsupportedOperationException("TODO");
    }
}

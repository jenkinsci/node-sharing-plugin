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

import com.redhat.jenkins.nodesharing.ActionFailed;
import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.RestEndpoint;
import com.redhat.jenkins.nodesharing.transport.DiscoverRequest;
import com.redhat.jenkins.nodesharing.transport.DiscoverResponse;
import com.redhat.jenkins.nodesharing.transport.ExecutorEntity;
import com.redhat.jenkins.nodesharing.transport.NodeStatusRequest;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadResponse;
import com.redhat.jenkins.nodesharing.transport.ReturnNodeRequest;
import hudson.Util;
import jenkins.model.JenkinsLocationConfiguration;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Receive and send REST commands from/to Orchestrator Jenkins.
 */
@Restricted(NoExternalUse.class)
// TODO Check permission
public class Api {

    private static final Logger LOGGER = Logger.getLogger(Api.class.getName());
    private final @Nonnull ExecutorEntity.Fingerprint fingerprint;

    private final SharedNodeCloud cloud;
    private final RestEndpoint rest;
    private final String version;

    public Api(@Nonnull final ConfigRepo.Snapshot snapshot,
               @Nonnull final String configRepoUrl,
               @Nonnull final SharedNodeCloud cloud
    ) {
        this.cloud = cloud;

        try {
            Properties properties = new Properties();
            properties.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));
            version = properties.getProperty("version");
            if (version == null) throw new AssertionError("No version in assembly properties");
        } catch (IOException e) {
            throw new AssertionError("Cannot load assembly properties", e);
        }

        this.fingerprint = new ExecutorEntity.Fingerprint(
                configRepoUrl,
                version,
                snapshot.getJenkinsByUrl(JenkinsLocationConfiguration.get().getUrl()).getName()
        );
        rest = new RestEndpoint(snapshot.getOrchestratorUrl(), "node-sharing-orchestrator");


    }

    //// Outgoing

    /**
     * Query Executor Jenkins to report the status of shared node.
     */
    // TODO What is it what we are REALLY communicating by throwing/returning int on POST level?
    public void doNodeStatus(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
        NodeStatusRequest request = com.redhat.jenkins.nodesharing.transport.Entity.fromInputStream(
                req.getInputStream(), NodeStatusRequest.class);
        String nodeName = Util.fixEmptyAndTrim(request.getNodeName());
        NodeStatusResponse.Status status = NodeStatusResponse.Status.NOT_FOUND;
        if (nodeName != null)
            status = cloud.getNodeStatus(request.getNodeName());
        NodeStatusResponse response = new NodeStatusResponse(fingerprint, request.getNodeName(), status);
        rsp.setContentType("application/json");
        response.toOutputStream(rsp.getOutputStream());
    }

//    /**
//     * Query Executor Jenkins to report the status of executed item.
//     */
//    // TODO What is it what we are REALLY communicating by throwing/returning int on POST level?
//    public void doRunStatus(@Nonnull final StaplerRequest req, @Nonnull final StaplerResponse rsp) throws IOException {
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
     * Put the queue items to Orchestrator
     */
    // TODO Response never used as there is likely nothing to report - consider async request
    public ReportWorkloadResponse reportWorkload(@Nonnull final ReportWorkloadRequest.Workload workload) {
        final ReportWorkloadRequest request = new ReportWorkloadRequest(fingerprint, workload);
        return rest.executeRequest(rest.post("reportWorkload"), ReportWorkloadResponse.class, request);
    }

    /**
     * Request to discover the state of the Orchestrator.
     *
     * @return Discovery result.
     */
    public DiscoverResponse discover() throws ActionFailed {
        return rest.executeRequest(
                rest.post("discover"),
                DiscoverResponse.class,
                new DiscoverRequest(fingerprint)
        );
    }

    /**
     * Send request to return node. No response needed.
     */
    public void returnNode(@Nonnull final String name, @Nonnull ReturnNodeRequest.Status status) {
        rest.executeRequest(rest.post("returnNode"), null, new ReturnNodeRequest(fingerprint, name, status));
    }

    //// Incoming

    /**
     * Request to execute #Item from the queue
     */
    @RequirePOST
    public void doExecution(@Nonnull @QueryParameter final String nodeName,
                            @Nonnull @QueryParameter final String id) {
        // TODO Create a Node based on the info and execute the Item
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Immediately return node to orchestrator. (Nice to have feature)
     *
     * @param name Name of the node to be returned.
     */
    @RequirePOST
    public void doReturnNode(@Nonnull @QueryParameter("name") final String name) {
        throw new UnsupportedOperationException("TODO");
/*
        Computer c = Jenkins.getInstance().getComputer(name);
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
        executable.complete(owner, state);
*/
    }
}

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
package com.redhat.jenkins.nodesharing;

import com.redhat.jenkins.nodesharing.transport.Entity;
import com.redhat.jenkins.nodesharing.transport.NodeStatusRequest;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import com.redhat.jenkins.nodesharing.transport.RunStatusRequest;
import com.redhat.jenkins.nodesharing.transport.RunStatusResponse;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule.MockTask;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.OneShotEvent;
import org.jenkinsci.plugins.gitclient.GitClient;
import hudson.model.Queue;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestBuilder;

import javax.annotation.Nonnull;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @author pjanouse
 */
public class SharedNodeCloudTest {

    private static final String PROPERTY_VERSION = "version";
    private static final String ORCHESTRATOR_URI = "node-sharing-orchestrator";

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    /**
     * Do POST HTTP request on target.
     *
     * @param target The request.
     * @param entity JSON string.
     *
     * @return Response from the server.
     */
    @Nonnull
    public Response doPostRequest(@Nonnull final WebTarget target, @Nonnull final Object entity) {
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
    public Response doPostRequest(@Nonnull final WebTarget target, @Nonnull final Object entity,
                                               @Nonnull final Response.Status status) {
        Response response = target.queryParam(PROPERTY_VERSION, "4.2")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(javax.ws.rs.client.Entity.json(entity));
        if (!status.equals(Response.Status.fromStatusCode(response.getStatus()))) {
            throw new ActionFailed.CommunicationError("Performing POST request '" + target.toString()
                    + "' returns unexpected response status '" + response.getStatus()
                    + "' [" + response.readEntity(String.class) + "]");
        }
        return response;
    }

    private static final class BlockingBuilder extends TestBuilder {
        private OneShotEvent start = new OneShotEvent();
        private OneShotEvent end = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            start.signal();
            end.block();
            return true;
        }
    }

    ////

    @Test
    public void doTestConnection() throws Exception {
        j.jenkins.setCrumbIssuer(null); // TODO
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));

        final Properties prop = new Properties();
        prop.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));

        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(gitClient.getWorkTree().getRemote()).getMessage(),
                containsString("Orchestrator version is " + prop.getProperty("version"))
        );
    }

    @Test
    public void doTestConnectionInvalidUrl() throws Exception {
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection("file:\\\\aaa").getMessage(),
                equalTo("Invalid config repo url")
        );
    }

    @Test
    public void doTestConnectionNonExistsUrl() throws Exception {
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection("file://dummy_not_exists").getMessage(),
                containsString("Unable to update config repo from")
        );
    }

    @Ignore
    @Test
    public void doTestConnectionImproperContentRepo() throws Exception {
        GitClient cr = configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins);
        FilePath workTree = cr.getWorkTree();
        workTree.child("config").delete();

        // TODO Commit fails due to untracked config file delete action
        cr.add("*");
        cr.commit("Hehehe");
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(workTree.getRemote()).getMessage(),
                containsString("No file named 'config' found in Config Repository")
        );
    }

    @Test
    public void doTestConnectionConfigRepoUrlMismatch() throws Exception {
        j.jenkins.setCrumbIssuer(null); // TODO
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        GitClient differentRepoUrlForClient = configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins);

        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        FormValidation validation = descr.doTestConnection(differentRepoUrlForClient.getWorkTree().getRemote());
        assertThat(validation.kind, equalTo(FormValidation.Kind.WARNING));
        assertThat(validation.getMessage(), startsWith("Orchestrator is configured from"));
    }

    // TODO Implementation isn't completed
    @Test
    public void doReportWorkloadTest() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.setCrumbIssuer(null);
        List<Queue.Item> qli = new ArrayList<Queue.Item>();
        MockTask task = new MockTask(j.DUMMY_OWNER, Label.get("solaris11"));
        qli.add(new MockTask(j.DUMMY_OWNER, Label.get("solaris11")).schedule());
        qli.add(new MockTask(j.DUMMY_OWNER, Label.get("solaris11")).schedule());
        assertThat(
                cloud.getApi().reportWorkload(qli),
                equalTo(Response.Status.OK)
        );
    }

    @Test
    public void nodeStatusTest() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.setCrumbIssuer(null);

//        for (Node n : j.jenkins.getNodes()) {
//            System.out.println(n.getNodeName());
//        }

        // NOT_FOUND status
        assertNull(j.jenkins.getComputer("foo"));
        checkNodeStatus(cloud, "foo", NodeStatusResponse.Status.NOT_FOUND);

        // IDLE status
        assertTrue(j.jenkins.getComputer("solaris1.orchestrator").isIdle());
        checkNodeStatus(cloud, "solaris1.orchestrator", NodeStatusResponse.Status.IDLE);

        // still IDLE status although offline
        j.jenkins.getComputer("solaris1.orchestrator").setTemporarilyOffline(true);
        j.jenkins.getComputer("solaris1.orchestrator").waitUntilOffline();
        assertTrue(j.jenkins.getComputer("solaris1.orchestrator").isOffline());
        checkNodeStatus(cloud, "solaris1.orchestrator", NodeStatusResponse.Status.IDLE);
        j.jenkins.getComputer("solaris1.orchestrator").setTemporarilyOffline(false);
        j.jenkins.getComputer("solaris1.orchestrator").waitUntilOnline();
        assertTrue(j.jenkins.getComputer("solaris1.orchestrator").isOnline());

        // BUSY status
        DumbSlave slave = j.createSlave("aNode", "", null);
        slave.toComputer().waitUntilOnline();
        assertTrue(slave.toComputer().isOnline());
        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedNode(slave);
        BlockingBuilder builder = new BlockingBuilder();
        job.getBuildersList().add(builder);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        assertTrue(job.isBuilding());
        assertFalse(slave.toComputer().isIdle());
        checkNodeStatus(cloud, "aNode", NodeStatusResponse.Status.BUSY);

        // OFFLINE status
        assertFalse(slave.toComputer().isIdle());
        slave.toComputer().setTemporarilyOffline(true);
        slave.toComputer().waitUntilOffline();
        assertTrue(slave.toComputer().isOffline());
        checkNodeStatus(cloud, "aNode", NodeStatusResponse.Status.OFFLINE);

        builder.end.signal();

        // CONNECTING status
        BlockingCommandLauncher blockingLauncher = new BlockingCommandLauncher(j.createComputerLauncher(null).getCommand());
        ConnectingSlave connectingSlave = new ConnectingSlave("aConnectingNode", "dummy",
                j.createTmpDir().getPath(), "1", Node.Mode.NORMAL, "",
                blockingLauncher, RetentionStrategy.NOOP, Collections.EMPTY_LIST);
        j.jenkins.addNode(connectingSlave);
        blockingLauncher.start.block();
        assertTrue(connectingSlave.toComputer().isConnecting());
        checkNodeStatus(cloud, "aConnectingNode", NodeStatusResponse.Status.CONNECTING);
        blockingLauncher.end.signal();
        connectingSlave.toComputer().waitUntilOnline();
    }

    private void checkNodeStatus(
            @Nonnull SharedNodeCloud cloud,
            @Nonnull final String nodeName,
            @Nonnull final NodeStatusResponse.Status nodeStatus
    ) {
        assertThat(
                cloud.getNodeStatus(nodeName),
                equalTo(nodeStatus)
        );
        assertThat(
                Entity.fromInputStream(
                        (InputStream) doPostRequest(
                                j.getCloudWebClient(cloud).path(NodeStatusRequest.REQUEST_URI),
                                new NodeStatusRequest(
                                    Pool.getInstance().getConfigEndpoint(),
                                    "4.2",
                                    nodeName
                                )).getEntity(),
                        NodeStatusResponse.class).getStatus(),
                equalTo(nodeStatus)
        );
        assertThat(
                Api.getInstance().nodeStatus(new ExecutorJenkins(j.jenkins.getRootUrl(), cloud.getName()), nodeName),
                equalTo(nodeStatus)
        );
    }

    private class BlockingCommandLauncher extends CommandLauncher {
        private OneShotEvent start = new OneShotEvent();
        private OneShotEvent end = new OneShotEvent();

        public BlockingCommandLauncher(String command) {
            super(command);
        }

        @Override
        public void launch(SlaveComputer computer, final TaskListener listener) {
            start.signal();
            try {
                end.block();
            } catch (InterruptedException e) { }
            super.launch(computer, listener);
        }
    }

    private class ConnectingSlave extends Slave implements EphemeralNode {
        public ConnectingSlave(String name,
                               String nodeDescription,
                               String remoteFS,
                               String numExecutors,
                               Mode mode,
                               String labelString,
                               ComputerLauncher launcher,
                               RetentionStrategy retentionStrategy,
                               List<? extends NodeProperty<?>> nodeProperties
        ) throws IOException, Descriptor.FormException {
            super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher,
                    retentionStrategy, nodeProperties);
        }

        @Override
        public ConnectingSlave asNode() {
            return this;
        }
    }

    @Test
    public void runStatusTest() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.setCrumbIssuer(null);

        // NOT_FOUND status
        assertNull(j.jenkins.getQueue().getItem(-1));
        checkRunStatus(cloud, -1, RunStatusResponse.Status.NOT_FOUND);

        MockTask task = new MockTask(j.DUMMY_OWNER, Label.get("solaris11"));
        Queue.Item item = task.schedule();

        for (Queue.Item i : j.jenkins.getQueue().getItems()) {
            System.out.println(i.getId() + ": " + cloud.getRunStatus(i.getId()) + i.isBuildable());
        }

//        assertThat(
//                Communication.RunState.getStatus((Integer) cloud.getApi().runStatus("-1")),
//                equalTo(Communication.RunState.NOT_FOUND)
//        );
//        assertThat(
//                Communication.RunState.getStatus((Integer) cloud.getApi().runStatus(((Long) item.getId()).toString())),
//                equalTo(Communication.RunState.DONE)
//        );
//
//        boolean ex_thrown = false;
//        try {
//            Communication.RunState.getStatus((Integer) cloud.getApi().runStatus("Invalid"));
//            fail("Expected thrown exception!");
//        } catch (IllegalArgumentException e) {
//            ex_thrown = true;
//        }
//        assertThat(
//                ex_thrown,
//                equalTo(true)
//        );
    }

    private void checkRunStatus(
            @Nonnull SharedNodeCloud cloud,
            @Nonnull final long runId,
            @Nonnull final RunStatusResponse.Status runStatus
    ) {
        assertThat(
                cloud.getRunStatus(runId),
                equalTo(runStatus)
        );
        assertThat(
                Entity.fromInputStream(
                        (InputStream) doPostRequest(
                                j.getCloudWebClient(cloud).path(RunStatusRequest.REQUEST_URI),
                                new RunStatusRequest(
                                        Pool.getInstance().getConfigEndpoint(),
                                        "4.2",
                                        runId
                                )).getEntity(),
                        RunStatusResponse.class).getStatus(),
                equalTo(runStatus)
        );
        assertThat(
                Api.getInstance().runStatus(new ExecutorJenkins(j.jenkins.getRootUrl(), cloud.getName()), runId),
                equalTo(runStatus)
        );
    }
}

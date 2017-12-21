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

import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule.MockTask;
import com.redhat.jenkins.nodesharing.transport.NodeStatusRequest;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.FilePath;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import hudson.security.csrf.DefaultCrumbIssuer;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.util.Collections;
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

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    ////

    @Test
    public void doTestConnection() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));

        final Properties prop = new Properties();
        prop.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));

        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(gitClient.getWorkTree().getRemote()).getMessage(),
                containsString("Orchestrator version is " + prop.getProperty("version"))
        );
    }

    @Test
    public void testConnectionWithoutCrumbIssuer() throws Exception {
        j.jenkins.setCrumbIssuer(null);
        doTestConnection();
    }

    @Test
    public void testConnectionWithDefaultCrumbIssuer() throws Exception {
        j.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(false));
        doTestConnection();
    }

    @Test
    public void doTestConnectionInvalidUrl() throws Exception {
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection("file:\\\\aaa").getMessage(),
                startsWith("Invalid config repo url")
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

    @Test
    public void doTestConnectionImproperContentRepo() throws Exception {
        GitClient cr = configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins);
        FilePath workTree = cr.getWorkTree();
        workTree.child("config").delete();

        cr.add("config");
        cr.commit("Hehehe");
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(workTree.getRemote()).getMessage(),
                containsString("No file named 'config' found in Config Repository")
        );
    }

    @Test
    public void doTestConnectionConfigRepoUrlMismatch() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        GitClient differentRepoUrlForClient = configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins);

        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        FormValidation validation = descr.doTestConnection(differentRepoUrlForClient.getWorkTree().getRemote());
        assertThat(validation.kind, equalTo(FormValidation.Kind.WARNING));
        assertThat(validation.getMessage(), startsWith("Orchestrator is configured from"));
    }

    // TODO Implementation isn't completed
    @Test
    public void doReportWorkloadTest() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        ReportWorkloadRequest.Workload workload = new ReportWorkloadRequest.Workload();
        // TODO avoid using ReservationTask to simulate executor workload as that is meant for orchestrator
        workload.addItem(new MockTask(j.DUMMY_OWNER, Label.get("solaris11")).schedule());
        workload.addItem(new MockTask(j.DUMMY_OWNER, Label.get("solaris11")).schedule());
        workload.addItem(new MockTask(j.DUMMY_OWNER, Label.get("solaris11")).schedule());
        cloud.getApi().reportWorkload(workload); // 200 response enforced
    }

    @Test
    public void nodeStatusTest() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        // NOT_FOUND status
        assertNull(j.jenkins.getComputer("foo"));
        checkNodeStatus(cloud, "foo", NodeStatusResponse.Status.NOT_FOUND);

        // IDLE status
        assertTrue(j.jenkins.getComputer("solaris1.acme.com").isIdle());
        checkNodeStatus(cloud, "solaris1.acme.com", NodeStatusResponse.Status.IDLE);

        // still IDLE status although offline
        j.jenkins.getComputer("solaris1.acme.com").setTemporarilyOffline(true);
        j.jenkins.getComputer("solaris1.acme.com").waitUntilOffline();
        assertTrue(j.jenkins.getComputer("solaris1.acme.com").isOffline());
        checkNodeStatus(cloud, "solaris1.acme.com", NodeStatusResponse.Status.IDLE);
        j.jenkins.getComputer("solaris1.acme.com").setTemporarilyOffline(false);
        j.jenkins.getComputer("solaris1.acme.com").waitUntilOnline();
        assertTrue(j.jenkins.getComputer("solaris1.acme.com").isOnline());

        // BUSY status
        DumbSlave slave = j.createSlave("aNode", "", null);
        slave.toComputer().waitUntilOnline();
        assertTrue(slave.toComputer().isOnline());
        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedNode(slave);
        NodeSharingJenkinsRule.BlockingBuilder builder = new NodeSharingJenkinsRule.BlockingBuilder();
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
        NodeSharingJenkinsRule.BlockingCommandLauncher blockingLauncher = new NodeSharingJenkinsRule.BlockingCommandLauncher(j.createComputerLauncher(null).getCommand());
        NodeSharingJenkinsRule.ConnectingSlave connectingSlave = new NodeSharingJenkinsRule.ConnectingSlave("aConnectingNode", "dummy",
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
    ) throws Exception {
        assertThat(
                cloud.getNodeStatus(nodeName),
                equalTo(nodeStatus)
        );
        RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), "cloud/" + cloud.name + "/api");
        assertThat(
                rest.executeRequest(rest.post("nodeStatus"), new NodeStatusRequest(
                        Pool.getInstance().getConfigRepoUrl(),
                        "4.2",
                        nodeName
                ), NodeStatusResponse.class).getStatus(),
                equalTo(nodeStatus)
        );
        assertThat(
                Api.getInstance().nodeStatus(
                        new ExecutorJenkins(j.jenkins.getRootUrl(), cloud.getName(), cloud.getConfigRepoUrl()),
                        nodeName),
                equalTo(nodeStatus)
        );
    }

//    @Test
//    public void runStatusTest() throws Exception {
//        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
//        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
////        j.jenkins.setCrumbIssuer(null);
//
//        // NOT_FOUND status
//        assertNull(j.jenkins.getQueue().getItem(-1));
//        checkRunStatus(cloud, -1, RunStatusResponse.Status.NOT_FOUND);
//
//        MockTask task = new MockTask(j.DUMMY_OWNER, Label.get("solaris11"));
//        Queue.Item item = task.schedule();
//
//        for (Queue.Item i : j.jenkins.getQueue().getItems()) {
//            System.out.println(i.getId() + ": " + cloud.getRunStatus(i.getId()) + i.isBuildable());
//        }
//
////        RunState.getStatus((Integer) cloud.getApi().doRunStatus("-1")),
////                equalTo(RunState.NOT_FOUND)
////
////        boolean ex_thrown = false;
////        try {
////            RunState.getStatus((Integer) cloud.getApi().doRunStatus("Invalid"));
////            fail("Expected thrown exception!");
////        } catch (IllegalArgumentException e) {
////            ex_thrown = true;
////        }
////        assertThat(
////                Communication.RunState.getStatus((Integer) cloud.getApi().runStatus("-1")),
////                equalTo(Communication.RunState.NOT_FOUND)
////        );
////        assertThat(
////                Communication.RunState.getStatus((Integer) cloud.getApi().runStatus(((Long) item.getId()).toString())),
////                equalTo(Communication.RunState.DONE)
////        );
////
////        boolean ex_thrown = false;
////        try {
////            Communication.RunState.getStatus((Integer) cloud.getApi().runStatus("Invalid"));
////            fail("Expected thrown exception!");
////        } catch (IllegalArgumentException e) {
////            ex_thrown = true;
////        }
////        assertThat(
////                ex_thrown,
////                equalTo(true)
////        );
//    }
//
//    private void checkRunStatus(
//            @Nonnull SharedNodeCloud cloud,
//            @Nonnull final long runId,
//            @Nonnull final RunStatusResponse.Status runStatus
//    ) throws Exception {
//        assertThat(
//                cloud.getRunStatus(runId),
//                equalTo(runStatus)
//        );
//        RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), "cloud/" + cloud.name + "/api");
//        assertThat(
//                rest.executeRequest(rest.post("runStatus"), RunStatusResponse.class, new RunStatusRequest(
//                        Pool.getInstance().getConfigEndpoint(),
//                        "4.2",
//                        runId
//                )).getStatus(),
//                equalTo(runStatus)
//        );
//        assertThat(
//                Api.getInstance().runStatus(
//                        new ExecutorJenkins(j.jenkins.getRootUrl(), cloud.getName(), cloud.getConfigRepoUrl()),
//                        runId),
//                equalTo(runStatus)
//        );
//    }
}

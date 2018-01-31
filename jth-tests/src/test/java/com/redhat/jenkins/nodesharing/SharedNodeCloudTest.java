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
import com.redhat.jenkins.nodesharingfrontend.SharedComputer;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeFactory;
import com.redhat.jenkins.nodesharingfrontend.SharedOnceRetentionStrategy;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.security.csrf.DefaultCrumbIssuer;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.recipes.WithPlugin;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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
    public void nodeStatusTestNotFound() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        assertNull(j.jenkins.getComputer("foo"));
        checkNodeStatus(cloud, "foo", NodeStatusResponse.Status.NOT_FOUND);
    }

    @Test
    public void nodeStatusTestIdle() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.addNode(cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com")));
        Computer computer = j.jenkins.getComputer(cloud.getNodeName("solaris2.acme.com"));

        computer.waitUntilOnline();
        assertTrue(computer.isOnline());
        assertTrue(computer.isIdle());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.IDLE);

        // still IDLE status although offline
        assertFalse(computer.isConnecting());
        computer.setTemporarilyOffline(true);
        computer.waitUntilOffline();
        while (computer.isConnecting()) {
            Thread.sleep(50);
        }
        assertTrue(computer.isOffline());
        assertFalse(computer.isConnecting());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.IDLE);
    }

    @Test
    public void nodeStatusTestBusy() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.addNode(cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com")));
        Computer computer = j.jenkins.getComputer(cloud.getNodeName("solaris2.acme.com"));

//        for (Node n : Jenkins.getInstance().getNodes()) {
//            System.out.println(n.getNodeName());
//        }

        computer.waitUntilOnline();
        assertTrue(computer.isOnline());
        assertTrue(computer.isIdle());

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedNode(computer.getNode());
        NodeSharingJenkinsRule.BlockingBuilder builder = new NodeSharingJenkinsRule.BlockingBuilder();
        job.getBuildersList().add(builder);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        assertTrue(job.isBuilding());
        assertFalse(computer.isIdle());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.BUSY);
        builder.end.signal();
    }

    @Test
    public void nodeStatusTestOffline() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.addNode(cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com")));
        Computer computer = j.jenkins.getComputer(cloud.getNodeName("solaris2.acme.com"));

//        for (Node n : Jenkins.getInstance().getNodes()) {
//            System.out.println(n.getNodeName());
//        }

        computer.waitUntilOnline();
        assertTrue(computer.isOnline());
        assertTrue(computer.isIdle());
        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedNode(computer.getNode());
        NodeSharingJenkinsRule.BlockingBuilder builder = new NodeSharingJenkinsRule.BlockingBuilder();
        job.getBuildersList().add(builder);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        assertTrue(job.isBuilding());
        assertFalse(computer.isIdle());
        computer.setTemporarilyOffline(true);
        computer.waitUntilOffline();
        assertTrue(computer.isOffline());
        assertFalse(computer.isIdle());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.OFFLINE);
        builder.end.signal();
    }

    @Test
    public void nodeStatusTestConnecting() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        assertTrue(cloud.isOperational());
        final ProvisioningActivity.Id id = new ProvisioningActivity.Id(cloud.getName(),
                null, cloud.getNodeName("aConnectingNode"));
        NodeSharingJenkinsRule.BlockingCommandLauncher blockingLauncher =
                new NodeSharingJenkinsRule.BlockingCommandLauncher(j.createComputerLauncher(null).getCommand());

//        SharedNode connectingSlave = new SharedNode(id, "dummy", j.createTmpDir().getPath(),
//                blockingLauncher, new SharedOnceRetentionStrategy(1), Collections.EMPTY_LIST);
//        SharedNode connectingSlave = SharedNodeFactory.transform(cloud.getLatestConfig().getNodes().get("solaris2.acme.com"));
        SharedNode connectingSlave = cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com"));
        connectingSlave.setLauncher(blockingLauncher);
        connectingSlave.setNodeName(cloud.getNodeName("aConnectingNode"));
        j.jenkins.addNode(connectingSlave);
        assertTrue(connectingSlave.toComputer().isConnecting());
        blockingLauncher.start.block();
        checkNodeStatus(cloud, "aConnectingNode", NodeStatusResponse.Status.CONNECTING);
        blockingLauncher.end.signal();
        connectingSlave.toComputer().waitUntilOnline();
        assertTrue(connectingSlave.toComputer().isOnline());
    }

    private void checkNodeStatus(
            @Nonnull SharedNodeCloud cloud,
            @Nonnull final String nodeName,
            @Nonnull final NodeStatusResponse.Status nodeStatus
    ) throws Exception {

        // Test through direct call of cloud impl.
        assertThat(
                cloud.getNodeStatus(nodeName),
                equalTo(nodeStatus)
        );

        // Test through plugin frontend API
        RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), "cloud/" + cloud.name + "/api");
        assertThat(
                rest.executeRequest(rest.post("nodeStatus"), new NodeStatusRequest(
                        Pool.getInstance().getConfigRepoUrl(),
                        "4.2",
                        nodeName
                ), NodeStatusResponse.class).getStatus(),
                equalTo(nodeStatus)
        );

        // Test through plugin backend API
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

    @Test
    public void testGetByName() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        assertTrue(cloud.isOperational());

        assertThat(SharedNodeCloud.getByName(cloud.name), equalTo(cloud));
        assertThat(SharedNodeCloud.getByName("foo"), equalTo(null));
        assertThat(SharedNodeCloud.getByName(""), equalTo(null));
        assertThat(SharedNodeCloud.getByName(null), equalTo(null));
    }

    @Test
    public void testGetNodeName() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        assertTrue(cloud.isOperational());

        assertThat(cloud.getNodeName("foo"), equalTo("foo-" + cloud.name));
        assertThat(cloud.getNodeName(""), equalTo("-" + cloud.name));
        assertThat(cloud.getNodeName(null), equalTo("-" + cloud.name));
        assertThat(cloud.getNodeName(" "), equalTo(" -" + cloud.name));
        assertThat(cloud.getNodeName("-"), equalTo("--" + cloud.name));
        assertThat(cloud.getNodeName(":"), equalTo(":-" + cloud.name));
        assertThat(cloud.getNodeName("- "), equalTo("- -" + cloud.name));
        assertThat(cloud.getNodeName(": "), equalTo(": -" + cloud.name));
        assertThat(cloud.getNodeName("-:"), equalTo("-:-" + cloud.name));
    }

    @Ignore
    @Test
    public void myTest() throws Exception {
//        String source = "<com.redhat.jenkins.nodesharingfrontend.SharedNode>\n" +
//                "  <name>solaris1.executor.com</name>\n" +
//                "  <description/>\n" +
//                "  <remoteFS>/var/jenkins-workspace</remoteFS>\n" +
//                "  <numExecutors>1</numExecutors>\n" +
//                "  <mode>EXCLUSIVE</mode>\n" +
//                "  <launcher class=\"hudson.plugins.sshslaves.SSHLauncher\" plugin=\"ssh-slaves@1.21\">\n" +
//                "    <host></host>\n" +
//                "    <port>22</port>\n" +
//                "    <credentialsId></credentialsId>\n" +
//                "    <javaPath>/path/to/launcher</javaPath>\n" +
//                "    <launchTimeoutSeconds>600</launchTimeoutSeconds>\n" +
//                "    <maxNumRetries>0</maxNumRetries>\n" +
//                "    <retryWaitTime>0</retryWaitTime>\n" +
//                "  </launcher>\n" +
//                "  <label>solaris11 sparc</label>\n" +
//                "  <nodeProperties/>\n" +
//                "</com.redhat.jenkins.nodesharingfrontend.SharedNode>";
//        source = source.replace("<name>solaris1.executor.com", "<name>solaris1.redhat.com");
//        Node result = (Node) Jenkins.XSTREAM2.fromXML(source);
//        if (result== null) {
//            System.out.println("Result is NULL!");
//        } else {
//            System.out.println("Name result: " + result.getNodeName());
//        }
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

//        for (NodeDefinition nd : cloud.getLatestConfig().getNodes().values()) {
//            System.out.println(nd.getName() + ": " + nd.getDefinition());
//        }

        String xmlDef = cloud.getLatestConfig().getNodes().get("solaris2.acme.com").getDefinition();

        j.jenkins.addNode(cloud.createNode(new NodeDefinition.Xml("solaris2.acme.com",  xmlDef)));


//        for (Node n : Jenkins.getInstance().getNodes()) {
//            System.out.println(n.getNodeName());
//        }

        Computer computer = j.jenkins.getComputer(cloud.getNodeName("solaris2.acme.com"));
//        ((SharedNode) computer.getNode()).setCloudName(cloud.getName());

        computer.waitUntilOnline();

    }
}


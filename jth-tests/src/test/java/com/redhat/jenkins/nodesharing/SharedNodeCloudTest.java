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

import com.redhat.jenkins.nodesharing.transport.NodeStatusRequest;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharing.transport.UtilizeNodeRequest;
import com.redhat.jenkins.nodesharing.transport.UtilizeNodeResponse;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharing.utils.NodeSharingJenkinsRule;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharingfrontend.SharedOnceRetentionStrategy;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.ws_cleanup.DisableDeferredWipeoutNodeProperty;
import hudson.security.AuthorizationStrategy;
import hudson.security.LegacySecurityRealm;
import hudson.security.csrf.DefaultCrumbIssuer;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.redhat.jenkins.nodesharing.ReservationVerifierTest.logged;
import static com.redhat.jenkins.nodesharing.ReservationVerifierTest.notLogged;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author pjanouse
 */
public class SharedNodeCloudTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public LoggerRule l = new LoggerRule();

    @Test
    public void doTestConnection() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);

        final Properties prop = new Properties();
        prop.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));

        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(gitClient.getWorkTree().getRemote(), j.getRestCredentialId()).getMessage(),
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
                descr.doTestConnection("file:\\\\aaa", j.getRestCredentialId()).getMessage(),
                startsWith("Invalid config repo url")
        );
    }

    @Test
    public void doTestConnectionBrokenUrl() throws Exception {
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection("file://dummy_not_exists", j.getRestCredentialId()).getMessage(),
                containsString("Unable to update config repo from")
        );
    }

    @Test
    public void doTestConnectionImproperContentRepo() throws Exception {
        GitClient cr = j.singleJvmGrid(j.jenkins);
        FilePath workTree = cr.getWorkTree();
        workTree.child("config").delete();

        cr.add("config");
        cr.commit("Hehehe");
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(workTree.getRemote(), j.getRestCredentialId()).getMessage(),
                containsString("No file named 'config' found in Config Repository")
        );
    }

    @Test
    public void doTestConnectionConfigRepoUrlMismatch() throws Exception {
        GitClient repo = j.singleJvmGrid(j.jenkins);
        String orchestratorUrl = Pool.getInstance().getConfigRepoUrl();
        FilePath executorConfigRepo = repo.getWorkTree().child("different_uri");
        repo.getWorkTree().copyRecursiveTo(executorConfigRepo);
        repo.getWorkTree().child(".git").copyRecursiveTo(executorConfigRepo.child(".git"));

        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        FormValidation validation = descr.doTestConnection(executorConfigRepo.getRemote(), j.getRestCredentialId());
        assertThat(validation.getMessage(), containsString(
                "Orchestrator is configured from " + orchestratorUrl + " but executor uses " + executorConfigRepo.getRemote()
        ));
        assertThat(validation.kind, equalTo(FormValidation.Kind.WARNING));
    }

    @Test
    public void doReportWorkloadTest() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        List<ReportWorkloadRequest.Workload.WorkloadItem> items = new ArrayList<>();
        items.add(new ReportWorkloadRequest.Workload.WorkloadItem(1, "test1", "solaris11"));
        items.add(new ReportWorkloadRequest.Workload.WorkloadItem(1, "test1", "solaris11"));
        items.add(new ReportWorkloadRequest.Workload.WorkloadItem(1, "test1", "solaris11"));
        ReportWorkloadRequest.Workload workload = new ReportWorkloadRequest.Workload.WorkloadBuilder(items).build();
        cloud.getApi().reportWorkload(workload); // 200 response enforced
    }

    @Test
    public void configRoundtrip() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud expected = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        j.jenkins.setSecurityRealm(new LegacySecurityRealm());
        j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
        j.configRoundtrip();

        SharedNodeCloud actual = SharedNodeCloud.getByName(expected.name);
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getConfigRepoUrl(), actual.getConfigRepoUrl());
        assertEquals(expected.getOrchestratorCredentialsId(), actual.getOrchestratorCredentialsId());
    }

    @Test
    public void nodeStatusTestNotFound() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        assertNull(j.jenkins.getComputer("foo"));
        checkNodeStatus(cloud, "foo", NodeStatusResponse.Status.NOT_FOUND);

        cloud.disabled(true);
        checkNodeStatus(cloud, "foo", NodeStatusResponse.Status.NOT_FOUND);
        cloud.disabled(false);
    }

    @Test
    public void nodeStatusTestIdle() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.addNode(cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com")));
        Computer computer = j.jenkins.getComputer(cloud.getNodeName("solaris2.acme.com"));

        computer.waitUntilOnline();
        assertTrue(computer.isOnline());
        assertTrue(computer.isIdle());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.IDLE);

        cloud.disabled(true);
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.IDLE);
        cloud.disabled(false);

        // still IDLE status although offline
        assertFalse(computer.isConnecting());
        //noinspection deprecation
        computer.setTemporarilyOffline(true, null);
        computer.waitUntilOffline();
        while (computer.isConnecting()) {
            Thread.sleep(50);
        }
        assertTrue(computer.isOffline());
        assertFalse(computer.isConnecting());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.IDLE);

        cloud.disabled(true);
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.IDLE);
        cloud.disabled(false);
    }

    @Test
    public void nodeStatusTestBusy() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.addNode(cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com")));
        Computer computer = j.jenkins.getComputer(cloud.getNodeName("solaris2.acme.com"));

        computer.waitUntilOnline();
        assertTrue(computer.isOnline());
        assertTrue(computer.isIdle());

        BlockingBuilder builder = j.getBlockingProject(computer.getNode());
        FreeStyleProject job = builder.getProject();
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        assertTrue(job.isBuilding());
        assertFalse(computer.isIdle());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.BUSY);

        cloud.disabled(true);
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.BUSY);
        cloud.disabled(false);

        builder.end.signal();
        j.waitUntilNoActivity();
    }

    @Test
    public void nodeStatusTestOffline() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.addNode(cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com")));
        Computer computer = j.jenkins.getComputer(cloud.getNodeName("solaris2.acme.com"));

        computer.waitUntilOnline();
        assertTrue(computer.isOnline());
        assertTrue(computer.isIdle());

        BlockingBuilder builder = j.getBlockingProject(computer.getNode());
        FreeStyleProject job = builder.getProject();
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        assertTrue(job.isBuilding());
        assertFalse(computer.isIdle());
        //noinspection deprecation
        computer.setTemporarilyOffline(true, null);
        computer.waitUntilOffline();
        assertTrue(computer.isOffline());
        assertFalse(computer.isIdle());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.OFFLINE);

        cloud.disabled(true);
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.OFFLINE);
        cloud.disabled(false);

        builder.end.signal();
    }

    @Test
    public void nodeStatusTestConnecting() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        NodeSharingJenkinsRule.BlockingCommandLauncher blockingLauncher = new NodeSharingJenkinsRule.BlockingCommandLauncher(
                ((SimpleCommandLauncher) j.createComputerLauncher(null)).cmd
        );

        SharedNode connectingSlave = cloud.createNode(cloud.getLatestConfig().getNodes().get("solaris2.acme.com"));
        connectingSlave.setLauncher(blockingLauncher);
        connectingSlave.setNodeName(cloud.getNodeName("aConnectingNode"));
        j.jenkins.addNode(connectingSlave);
        assertTrue(connectingSlave.toComputer().isConnecting());
        blockingLauncher.start.block();
        checkNodeStatus(cloud, "aConnectingNode", NodeStatusResponse.Status.CONNECTING);

        cloud.disabled(true);
        checkNodeStatus(cloud, "aConnectingNode", NodeStatusResponse.Status.CONNECTING);
        cloud.disabled(false);

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
        RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), "cloud/" + cloud.name + "/api", j.getRestCredential());

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
                        new ExecutorJenkins(j.jenkins.getRootUrl(), cloud.getName()),
                        nodeName),
                equalTo(nodeStatus)
        );
    }

    @Test
    public void testGetByName() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        assertThat(SharedNodeCloud.getByName(cloud.name), equalTo(cloud));
        assertThat(SharedNodeCloud.getByName("foo"), equalTo(null));
        assertThat(SharedNodeCloud.getByName(""), equalTo(null));
        assertThat(SharedNodeCloud.getByName(null), equalTo(null));
    }

    @Test
    public void testGetNodeName() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        assertThat(cloud.getNodeName("foo"), equalTo("foo-" + cloud.name));
        assertThat(cloud.getNodeName(""), equalTo("-" + cloud.name));
        assertThat(cloud.getNodeName(null), equalTo("null-" + cloud.name));
        assertThat(cloud.getNodeName(" "), equalTo(" -" + cloud.name));
        assertThat(cloud.getNodeName("-"), equalTo("--" + cloud.name));
        assertThat(cloud.getNodeName(":"), equalTo(":-" + cloud.name));
        assertThat(cloud.getNodeName("- "), equalTo("- -" + cloud.name));
        assertThat(cloud.getNodeName(": "), equalTo(": -" + cloud.name));
        assertThat(cloud.getNodeName("-:"), equalTo("-:-" + cloud.name));
    }

    @Test
    public void testCreateNode() throws Exception {
        String source = "<com.redhat.jenkins.nodesharingfrontend.SharedNode>\n" +
                "  <name>solaris1.redhat.com</name>\n" +
                "  <description/>\n" +
                "  <remoteFS>/var/jenkins-workspace</remoteFS>\n" +
                "  <numExecutors>1</numExecutors>\n" +
                "  <mode>EXCLUSIVE</mode>\n" +
                "  <launcher class=\"hudson.slaves.CommandLauncher\">\n" +
                "    <agentCommand />\n" +
                "    <env serialization=\"custom\">\n" +
                "      <unserializable-parents/>\n" +
                "      <tree-map>\n" +
                "        <default>\n" +
                "          <comparator class=\"hudson.util.CaseInsensitiveComparator\"/>\n" +
                "        </default>\n" +
                "        <int>0</int>\n" +
                "      </tree-map>\n" +
                "    </env>\n" +
                "  </launcher>\n" +
                "  <label>foo</label>\n" +
                "  <nodeProperties/>\n" +
                "</com.redhat.jenkins.nodesharingfrontend.SharedNode>";
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        SharedNode node = cloud.createNode(new NodeDefinition.Xml("ok-node.xml", source));
        assertThat(node, notNullValue());

        source = source.replace("SharedNode", "SharedNodeFoo");
        try {
            cloud.createNode(new NodeDefinition.Xml("failed-node.xml", source));
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("java.lang.IllegalArgumentException: Invalid node definition", e.toString());
        }
    }

    @Test
    public void testDoUtilizeNode() throws Exception {
        String source = "<com.redhat.jenkins.nodesharingfrontend.SharedNode>\n" +
                "  <name>solaris1.redhat.com</name>\n" +
                "  <description/>\n" +
                "  <remoteFS>/var/jenkins-workspace</remoteFS>\n" +
                "  <numExecutors>1</numExecutors>\n" +
                "  <mode>EXCLUSIVE</mode>\n" +
                "  <launcher class=\"hudson.slaves.CommandLauncher\">\n" +
                "    <agentCommand />\n" +
                "    <env serialization=\"custom\">\n" +
                "      <unserializable-parents/>\n" +
                "      <tree-map>\n" +
                "        <default>\n" +
                "          <comparator class=\"hudson.util.CaseInsensitiveComparator\"/>\n" +
                "        </default>\n" +
                "        <int>0</int>\n" +
                "      </tree-map>\n" +
                "    </env>\n" +
                "  </launcher>\n" +
                "  <label>foo</label>\n" +
                "  <nodeProperties/>\n" +
                "</com.redhat.jenkins.nodesharingfrontend.SharedNode>";

        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("foo"));
        job.scheduleBuild2(0).getStartCondition();
        assertFalse(job.isBuilding());

        RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), "cloud/" + cloud.name + "/api", j.getRestCredential());
        rest.executeRequest(rest.post("utilizeNode"), new UtilizeNodeRequest(
                Pool.getInstance().getConfigRepoUrl(),
                "4.2",
                new NodeDefinition.Xml("ok-node.xml", source)
        ), UtilizeNodeResponse.class);

        source  = source.replace("SharedNode", "SharedNodeFoo");
        try {
            rest = new RestEndpoint(j.getURL().toExternalForm(), "cloud/" + cloud.name + "/api", j.getRestCredential());
            rest.executeRequest(rest.post("utilizeNode"), new UtilizeNodeRequest(
                    Pool.getInstance().getConfigRepoUrl(),
                    "4.2",
                    new NodeDefinition.Xml("failed-node.xml", source)
            ), UtilizeNodeResponse.class);
            fail();
        } catch (ActionFailed.RequestFailed e) {
            assertThat(e.toString(), containsString("com.redhat.jenkins.nodesharing.ActionFailed$RequestFailed: Executing REST call POST"));
            assertThat(e.toString(), containsString("java.lang.IllegalArgumentException: Invalid node definition"));
        }
    }

    @Test
    public void testDoUtilizeNodeWhenCloudTemporaryDisabled() throws Exception {
        String source = "<com.redhat.jenkins.nodesharingfrontend.SharedNode>\n" +
                "  <name>solaris1.redhat.com</name>\n" +
                "  <description/>\n" +
                "  <remoteFS>/var/jenkins-workspace</remoteFS>\n" +
                "  <numExecutors>1</numExecutors>\n" +
                "  <mode>EXCLUSIVE</mode>\n" +
                "  <launcher class=\"hudson.slaves.CommandLauncher\">\n" +
                "    <agentCommand />\n" +
                "    <env serialization=\"custom\">\n" +
                "      <unserializable-parents/>\n" +
                "      <tree-map>\n" +
                "        <default>\n" +
                "          <comparator class=\"hudson.util.CaseInsensitiveComparator\"/>\n" +
                "        </default>\n" +
                "        <int>0</int>\n" +
                "      </tree-map>\n" +
                "    </env>\n" +
                "  </launcher>\n" +
                "  <label>foo</label>\n" +
                "  <nodeProperties/>\n" +
                "</com.redhat.jenkins.nodesharingfrontend.SharedNode>";

        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("foo"));
        job.scheduleBuild2(0).getStartCondition();
        assertFalse(job.isBuilding());

        cloud.disabled(true);
        try {
            RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), "cloud/" + cloud.name + "/api", j.getRestCredential());
            rest.executeRequest(rest.post("utilizeNode"), new UtilizeNodeRequest(
                    Pool.getInstance().getConfigRepoUrl(),
                    "4.2",
                    new NodeDefinition.Xml("ok-node.xml", source)
            ), UtilizeNodeResponse.class);
        } catch (ActionFailed.RequestFailed e) {
            assertThat(e.toString(), containsString("com.redhat.jenkins.nodesharing.ActionFailed$RequestFailed: Executing REST call POST"));
            assertThat(e.toString(), containsString("410 Gone"));
        }
        assertFalse(job.isBuilding());
    }

    @Test
    public void testTemporaryOffline() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        final SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        l.record(Logger.getLogger(SharedOnceRetentionStrategy.class.getName()), Level.INFO);
        l.capture(10);

        BlockingBuilder builder = j.getBlockingProject("solaris10");
        FreeStyleProject job = builder.getProject();
        QueueTaskFuture<FreeStyleBuild> jobFuture = job.scheduleBuild2(0);
        builder.start.block();
        assertTrue(job.isBuilding());
        Computer computer = jobFuture.getStartCondition().get().getBuiltOn().toComputer();
        assertFalse(computer.isIdle());
        checkNodeStatus(cloud, "solaris2.acme.com", NodeStatusResponse.Status.BUSY);
        assertTrue(computer.isOnline());

        computer.setTemporarilyOffline(true, new OfflineCause.ByCLI("Temp offline"));
        while (computer.isOnline()) {
            Thread.sleep(50);
        }
        assertTrue(computer.isOffline());
        builder.end.signal();
        while (!computer.isIdle()) {
            Thread.sleep(50);
        }
        assertTrue(computer.isIdle());
        assertFalse(job.isBuilding());
        assertTrue(computer.isOffline());
        assertThat(computer.getOfflineCauseReason(), is("Temp offline"));
        computer.setTemporarilyOffline(false, null);
        while (computer.isOffline()) {
            Thread.sleep(50);
        }
        assertTrue(computer.isOnline());
        ((SharedOnceRetentionStrategy) computer.getRetentionStrategy()).done((AbstractCloudComputer) computer);
        j.waitUntilNoActivity();
        assertThat(l, logged(Level.INFO, "termination of " + computer.getName() + " is postponed due to temporary offline state.*"));
        assertThat(l, logged(Level.INFO, "Terminating computer " + computer.getName() + ".*"));
    }

    @Test
    public void testNodeWipeout() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        l.record(Logger.getLogger(SharedNode.class.getName()), Level.INFO);
        l.capture(10);

        BlockingBuilder builder = j.getBlockingProject("wipeout");
        FreeStyleProject job = builder.getProject();
        QueueTaskFuture<FreeStyleBuild> jobFuture = job.scheduleBuild2(0);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        Computer computer = jobFuture.getStartCondition().get().getBuiltOn().toComputer();
        builder.end.signal();
        j.waitUntilNoActivity();
        assertThat(l, logged(Level.INFO, computer.getName() + ": Wipeout activated"));
        // TODO Check if the workspace is really deleted
    }

    @Test
    public void testNodeWipeoutByDefault() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        l.record(Logger.getLogger(SharedNode.class.getName()), Level.INFO);
        l.capture(10);

        BlockingBuilder builder = j.getBlockingProject("solaris11");
        FreeStyleProject job = builder.getProject();
        QueueTaskFuture<FreeStyleBuild> jobFuture = job.scheduleBuild2(0);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        Computer computer = jobFuture.getStartCondition().get().getBuiltOn().toComputer();
        builder.end.signal();
        j.waitUntilNoActivity();
        assertThat(l, logged(Level.INFO, computer.getName() + ": Wipeout activated"));
        // TODO Check if the workspace is really deleted
    }

    @Test
    public void testNodeWipeoutSkip() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        l.record(Logger.getLogger(SharedNode.class.getName()), Level.INFO);
        l.capture(10);

        BlockingBuilder builder = j.getBlockingProject("wipeoutskip");
        FreeStyleProject job = builder.getProject();
        QueueTaskFuture<FreeStyleBuild> jobFuture = job.scheduleBuild2(0);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        Computer computer = jobFuture.getStartCondition().get().getBuiltOn().toComputer();
        builder.end.signal();
        j.waitUntilNoActivity();
        assertThat(l, notLogged(Level.INFO, computer.getName() + ": Wipeout activated"));
        // TODO Check if the workspace isn't deleted
    }

    @Test
    public void testNodeHasAttachedDisableDeferredWipeoutNodeProperty() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        BlockingBuilder builder = j.getBlockingProject("solaris11");
        FreeStyleProject job = builder.getProject();
        QueueTaskFuture<FreeStyleBuild> jobFuture = job.scheduleBuild2(0);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        assertThat(
                jobFuture.getStartCondition().get().getBuiltOn().getNodeProperties(),
                hasItem(isA(DisableDeferredWipeoutNodeProperty.class)));
        builder.end.signal();
        j.waitUntilNoActivity();
    }

    @Test
    public void testNodeHasNotAttachedDisableDeferredWipeoutNodeProperty() throws Exception {
        final GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        cloud.isWsCleanupAvailable = false;
        BlockingBuilder builder = j.getBlockingProject("solaris11");
        FreeStyleProject job = builder.getProject();
        QueueTaskFuture<FreeStyleBuild> jobFuture = job.scheduleBuild2(0);
        job.scheduleBuild2(0).getStartCondition();
        builder.start.block();
        assertThat(
                jobFuture.getStartCondition().get().getBuiltOn().getNodeProperties(),
                not(hasItem(isA(DisableDeferredWipeoutNodeProperty.class))));
        builder.end.signal();
        j.waitUntilNoActivity();
    }
}

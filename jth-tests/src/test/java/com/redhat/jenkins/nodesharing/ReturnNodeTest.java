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

import com.google.common.base.Joiner;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ShareableNode;
import com.redhat.jenkins.nodesharingfrontend.Api;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ReturnNodeTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Test
    public void returnNodeThatDoesNotExist() throws Exception {
        j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(Pool.getInstance().getConfigRepoUrl());
        assertNotNull(cloud.getOrchestratorCredentialsId());
        NodeDefinition def = Pool.getInstance().getConfig().getNodes().values().iterator().next();

        // Removing node that does not exists should not fail as that is expected when removed from inventory
        SharedNode foo = spy(cloud.createNode(def));
        when(foo.getHostName()).thenReturn("no_such_node");
        assertEquals(null, j.jenkins.getNode(foo.getHostName()));
        cloud.getApi().returnNode(foo);

        // Removing node of different type is an error
        DumbSlave slave = j.createOnlineSlave();
        j.jenkins.removeNode(slave);
        slave.setNodeName("foo");
        j.jenkins.addNode(slave);

        try {
            when(foo.getHostName()).thenReturn("foo");
            cloud.getApi().returnNode(foo);
            fail();
        } catch (ActionFailed.RequestFailed ex) {
            // Expected
        }

        // Returning free node should pass
        ShareableNode shareableNode = new ShareableNode(def);
        j.jenkins.addNode(shareableNode);
        foo = cloud.createNode(def);
        cloud.getApi().returnNode(foo); // No error as it is idle already
    }

    @Test
    public void releaseRunningNode() throws Exception {
        j.singleJvmGrid(j.jenkins);
        String configEndpoint = Pool.getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configEndpoint);

        // Node is reserved
        NodeDefinition nodeDefinition = Pool.getInstance().getConfig().getNodes().values().iterator().next();
        ShareableNode shareableNode = new ShareableNode(nodeDefinition);
        j.jenkins.addNode(shareableNode);
        Label taskLabel = Label.get(Joiner.on("&&").join(nodeDefinition.getLabelAtoms()));
        ReservationTask task = new ReservationTask(new ExecutorJenkins(j.getURL().toExternalForm(), "name"), taskLabel, "foo", 1L);
        QueueTaskFuture<Queue.Executable> reservationFuture = task.schedule().getFuture();
        reservationFuture.getStartCondition().get(1, TimeUnit.SECONDS);
        assertFalse(j.jenkins.getComputer(shareableNode.getNodeName()).isIdle());

        SharedNode shared = cloud.createNode(nodeDefinition);
        cloud.getApi().returnNode(shared);
        reservationFuture.get(1, TimeUnit.SECONDS);
        Thread.sleep(500);

        assertTrue(j.jenkins.getComputer(shareableNode.getNodeName()).isIdle());
    }

    @Test
    public void doNotCompleteReservationNotOwnedByReportingExecutor() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        gitClient.getWorkTree().child("jenkinses").child("other").write("url=https://foo.com\n", "UTF-8");
        gitClient.add("jenkinses");
        gitClient.commit("Add other jenkins");

        String configEndpoint = Pool.getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configEndpoint);

        BlockingBuilder<FreeStyleProject> bb = j.getBlockingProject("solaris11");
        FreeStyleBuild b = bb.getProject().scheduleBuild2(0).getStartCondition().get();
        bb.start.block();

        assertEquals(1, j.getActiveReservations().size());

        Api differentJenkinsApi = new Api(cloud.getLatestConfig(), configEndpoint, cloud, "https://foo.com");
        try {
            differentJenkinsApi.returnNode(((SharedNode) b.getBuiltOn()));
            fail();
        } catch (com.redhat.jenkins.nodesharing.ActionFailed.RequestFailed ex) {
            assertEquals(HttpServletResponse.SC_CONFLICT, ex.getStatusCode());
        }

        assertEquals(1, j.getActiveReservations().size());

        bb.end.signal();
        j.waitUntilNoActivity();
    }
}

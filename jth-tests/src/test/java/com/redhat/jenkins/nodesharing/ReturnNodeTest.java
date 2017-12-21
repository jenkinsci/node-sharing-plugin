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
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ShareableNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProperty;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReturnNodeTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test
    public void returnNodeThatDoesNotExist() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(Pool.getInstance().getConfigRepoUrl());

        // Removing node that does not exists should not fail as that is expected when removed from inventory
        SharedNode foo = new SharedNode(
                new ProvisioningActivity.Id("c", "t", "foo"), "", "", null, null, Collections.<NodeProperty<?>>emptyList()
        );
        cloud.getApi().returnNode(foo);

        // Removing node of different type is an error
        DumbSlave slave = j.createOnlineSlave();
        j.jenkins.removeNode(slave);
        slave.setNodeName("foo");
        j.jenkins.addNode(slave);

        try {
            cloud.getApi().returnNode(foo);
            fail();
        } catch (ActionFailed.RequestFailed ex) {
            // Expected
        }

        // Node is free
        ShareableNode shareableNode = new ShareableNode(Pool.getInstance().getConfig().getNodes().values().iterator().next());
        j.jenkins.addNode(shareableNode);
        foo = new SharedNode(
                new ProvisioningActivity.Id("c", "t", shareableNode.getNodeName()), "", "", null, null, Collections.<NodeProperty<?>>emptyList()
        );
        cloud.getApi().returnNode(foo); // No error as it is idle already
    }

    @Test
    public void releaseRunningNode() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        String configEndpoint = Pool.getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configEndpoint);

        // Node is reserved
        NodeDefinition nodeDefinition = Pool.getInstance().getConfig().getNodes().values().iterator().next();
        ShareableNode shareableNode = new ShareableNode(nodeDefinition);
        j.jenkins.addNode(shareableNode);
        Label taskLabel = Label.get(Joiner.on("&&").join(nodeDefinition.getLabelAtoms()));
        ReservationTask task = new ReservationTask(new ExecutorJenkins(j.getURL().toExternalForm(), "name", configEndpoint), taskLabel, "foo");
        QueueTaskFuture<Queue.Executable> reservationFuture = task.schedule().getFuture();
        reservationFuture.getStartCondition().get(1, TimeUnit.SECONDS);
        assertFalse(j.jenkins.getComputer(shareableNode.getNodeName()).isIdle());

        SharedNode shared = new SharedNode(
                new ProvisioningActivity.Id("c", "t", shareableNode.getNodeName()), "", "", null, null, Collections.<NodeProperty<?>>emptyList()
        );
        cloud.getApi().returnNode(shared);
        reservationFuture.get(1, TimeUnit.SECONDS);
        Thread.sleep(500);

        assertTrue(j.jenkins.getComputer(shareableNode.getNodeName()).isIdle());
    }
}

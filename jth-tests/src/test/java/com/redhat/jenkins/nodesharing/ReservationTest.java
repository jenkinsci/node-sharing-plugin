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

import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharing.utils.NodeSharingJenkinsRule;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ReservationTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Test
    public void configRepoIsolation() throws Exception {
        GitClient cr = j.singleJvmGrid(j.jenkins);
        StringWriter capture = new StringWriter();
        cr.changelog().to(capture).execute();
        String output = capture.toString();
        assertThat(output, containsString("author Pool Maintainer <pool.maintainer@acme.com>"));
        assertThat(output, containsString("committer Pool Maintainer <pool.maintainer@acme.com>"));
    }

    @Test
    public void doTestConnection() throws Exception {
        GitClient cr = j.singleJvmGrid(j.jenkins);

        final Properties prop = new Properties();
        prop.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));

        SharedNodeCloud.DescriptorImpl descriptor = (SharedNodeCloud.DescriptorImpl) j.jenkins.getDescriptorOrDie(SharedNodeCloud.class);
        FormValidation validation = descriptor.doTestConnection(cr.getWorkTree().getRemote(), j.getRestCredentialId());
        assertThat(validation.renderHtml(), containsString("Orchestrator version is " + prop.getProperty("version")));
    }

    @Test
    public void runBuildSuccessfully() throws Exception {
        j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(Pool.getInstance().getConfigRepoUrl());

        // When I schedule a bunch of tasks on executor
        Label winLabel = Label.get("w2k12");
        BlockingBuilder winBuilder = j.getBlockingProject(winLabel.getExpression());
        FreeStyleProject winJob = winBuilder.getProject();

        Label solarisLabel = Label.get("solaris11&&!(x86||x86_64)");
        BlockingBuilder solarisBuilder = j.getBlockingProject(solarisLabel.getExpression());
        FreeStyleProject solarisJob = solarisBuilder.getProject();

        BlockingBuilder solaris2Builder = j.getBlockingProject(solarisLabel.getExpression());
        FreeStyleProject solaris2Job = solaris2Builder.getProject();

        QueueTaskFuture<FreeStyleBuild> winBuildFuture = winJob.scheduleBuild2(0);
        QueueTaskFuture<FreeStyleBuild> solBuildFuture =  solarisJob.scheduleBuild2(0);
        QueueTaskFuture<FreeStyleBuild> scheduledSolBuildFuture = solaris2Job.scheduleBuild2(0);
        Thread.sleep(1000);

        assertEquals(3, j.jenkins.getQueue().getBuildableItems().size());

        j.reportWorkloadToOrchestrator();
        Thread.sleep(1000);

        // Then there should be reservation tasks on orchestrator
        assertEquals(2, j.getActiveReservations().size());
        assertEquals(1, j.getQueuedReservations().size());
        assertEquals(solarisLabel, j.getComputer("solaris1.acme.com").getReservation().getParent().getAssignedLabel());
        assertEquals(winLabel, j.getComputer("win1.acme.com").getReservation().getParent().getAssignedLabel());

        // Unblocked builds are run, the rest is not
        FreeStyleBuild winBuild = winBuildFuture.getStartCondition().get();
        FreeStyleBuild solBuild = solBuildFuture.getStartCondition().get();
        assertFalse(scheduledSolBuildFuture.getStartCondition().isDone());
        Thread.sleep(1000);

        // They start occupying real computers or they stay in the queue
        assertSame(j.jenkins.getNode("win1.acme.com-" + cloud.name), winBuild.getBuiltOn());
        assertSame(j.jenkins.getNode("solaris1.acme.com-" + cloud.name), solBuild.getBuiltOn());
        assertNull(j.jenkins.getComputer("win2.acme.com-" + cloud.name));
        assertNull(j.jenkins.getComputer("solaris2.acme.com-" + cloud.name));

        // Synchronize with Orchestrator
        j.reportWorkloadToOrchestrator();
        Thread.sleep(1000);

        // Completing the executor build will remove the executor node and complete the reservation
        winBuilder.end.signal();
        winBuildFuture.get();
        j.assertBuildStatusSuccess(winJob.getBuildByNumber(1));
        Thread.sleep(1000);
        assertNull(j.jenkins.getNode("win1.acme.com-" + cloud.name));
        assertNull(j.getComputer("win1.acme.com").getReservation());

        assertEquals(1, j.getActiveReservations().size());
        assertEquals(1, j.getQueuedReservations().size());

        // When first solaris task completes
        solarisBuilder.end.signal();
        solBuildFuture.get();
        j.assertBuildStatusSuccess(solarisJob.getBuildByNumber(1));

        // Queued solaris build gets unblocked
        scheduledSolBuildFuture.getStartCondition().get();
        assertFalse(scheduledSolBuildFuture.isDone());
        Thread.sleep(1000);

        // Synchronize with Orchestrator
        j.reportWorkloadToOrchestrator();
        Thread.sleep(1000);

        assertEquals(1, j.getActiveReservations().size());
        assertEquals(0, j.getQueuedReservations().size());

        solaris2Builder.end.signal();
        j.assertBuildStatusSuccess(scheduledSolBuildFuture);
        j.waitUntilNoActivity();
        assertEquals(0, j.getActiveReservations().size());
        assertEquals(0, j.getQueuedReservations().size());
    }

    @Test
    public void reflectChangesInWorkloadReported() throws Exception {
        j.singleJvmGrid(j.jenkins);
        j.addSharedNodeCloud(Pool.getInstance().getConfigRepoUrl());

        Label label = Label.get("w2k12");

        // Create and run a blocking job so the state of Queue isn't changed anymore
        BlockingBuilder blockingBuilder = j.getBlockingProject(label.getExpression());
        FreeStyleProject blocking = blockingBuilder.getProject();
        blocking.scheduleBuild2(0);
        j.jenkins.getQueue().scheduleMaintenance().get(); // Make sure parallel #maintain will not change the order while using it
        j.reportWorkloadToOrchestrator();
        while (blocking.isInQueue() || !blocking.isBuilding()) {
            Thread.sleep(10);
        }

        FreeStyleProject remove = j.createFreeStyleProject("remove");
        FreeStyleProject introduce = j.createFreeStyleProject("introduce");
        FreeStyleProject keep = j.createFreeStyleProject("keep");
        remove.setAssignedLabel(label);
        introduce.setAssignedLabel(label);
        keep.setAssignedLabel(label);

        QueueTaskFuture<FreeStyleBuild> removeFuture = remove.scheduleBuild2(0);
        keep.scheduleBuild2(0);
        j.jenkins.getQueue().scheduleMaintenance().get(); // Make sure parallel #maintain will not change the order while using it

        // The same can be sent repeatedly without changing the queue
        for (int i = 0; i < 3; i++) {
            j.reportWorkloadToOrchestrator();
            j.jenkins.getQueue().scheduleMaintenance().get(); // Make sure parallel #maintain will not change the order while using it

            List<ReservationTask> scheduledReservations = j.getQueuedReservations();
            assertThat(scheduledReservations, Matchers.<ReservationTask>iterableWithSize(2));
            Queue.Item[] items = Jenkins.getActiveInstance().getQueue().getItems();
            assertThat(items, arrayWithSize(4));
            // Executor items
            assertEquals("remove", items[3].task.getName());
            assertEquals("keep", items[2].task.getName());
            // Orchestrator items
            assertEquals("remove", ((ReservationTask) items[1].task).getTaskName());
            assertEquals("keep", ((ReservationTask) items[0].task).getTaskName());
        }

        removeFuture.cancel(true);
        introduce.scheduleBuild2(0);
        j.jenkins.getQueue().scheduleMaintenance().get(); // Make sure parallel #maintain will not change the order while using it

        j.reportWorkloadToOrchestrator();
        j.jenkins.getQueue().scheduleMaintenance().get(); // Make sure parallel #maintain will not change the order while using it

        List<ReservationTask> scheduledReservations = j.getQueuedReservations();
        assertThat(scheduledReservations, Matchers.<ReservationTask>iterableWithSize(2));
        Queue.Item[] items = Jenkins.getActiveInstance().getQueue().getItems();
        assertThat(items, arrayWithSize(4));

        assertEquals("keep", items[3].task.getName());
        assertEquals("keep", ((ReservationTask) items[2].task).getTaskName());
        assertEquals("introduce", items[1].task.getName());
        assertEquals("introduce", ((ReservationTask) items[0].task).getTaskName());
    }

    @Test
    public void buildWithNoLabelShouldNotBeBuilt() throws Exception {
        j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(Pool.getInstance().getConfigRepoUrl());
        assertFalse(cloud.canProvision(null));

        j.jenkins.setNumExecutors(0);
        FreeStyleProject p = j.createFreeStyleProject();
        p.scheduleBuild2(0);
        Thread.sleep(1000);
        assertEquals(1, j.jenkins.getQueue().getBuildableItems().size());
        assertEquals(0, j.getActiveReservations().size());
        assertEquals(0, j.getQueuedReservations().size());
    }
}

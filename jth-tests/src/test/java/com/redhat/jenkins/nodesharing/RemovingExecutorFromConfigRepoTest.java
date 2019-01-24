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

import com.redhat.jenkins.nodesharing.transport.DiscoverResponse;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharing.utils.NodeSharingJenkinsRule;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingfrontend.Api;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharingfrontend.WorkloadReporter;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.queue.QueueTaskFuture;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;

import javax.servlet.http.HttpServletResponse;

import java.util.logging.Level;

import static com.redhat.jenkins.nodesharingbackend.Pool.getInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class RemovingExecutorFromConfigRepoTest {

    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();
    @Rule public LoggerRule l = new LoggerRule();

    @Test
    public void rejectDiscoverFromExecutorNotInConfigRepo() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        j.disableLocalExecutor(gitClient);

        String configRepoUrl = getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);

        Api api = cloud.getApi();

        DiscoverResponse discover = api.discover();
        assertThat(discover.getLabels(), Matchers.<String>emptyIterable());
        assertThat(discover.getDiagnosis(), containsString("Executor '" + j.getURL() + "' is not declared to be a member of the sharing pool in " + configRepoUrl));
    }

    @Test
    public void acceptDoReturnNodeFromExecutor() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);

        String configRepoUrl = getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);

        BlockingBuilder bb = j.getBlockingProject("solaris11");
        FreeStyleBuild b = bb.getProject().scheduleBuild2(0).getStartCondition().get();
        bb.start.block();

        assertEquals(1, j.getActiveReservations().size());

        j.disableLocalExecutor(gitClient);

        Api api = cloud.getApi();
        api.returnNode(((SharedNode) b.getBuiltOn()));
        Thread.sleep(100);
        assertEquals(0, j.getActiveReservations().size());

        bb.end.signal();
        j.waitUntilNoActivity();
    }

    @Test
    public void rejectWorkloadFromExecutorNotInConfigRepo() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        j.disableLocalExecutor(gitClient);

        String configRepoUrl = getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);

        Api api = cloud.getApi();
        try {
            api.reportWorkload(ReportWorkloadRequest.Workload.builder().build());
            fail("Reporting workload should fail for executor not part of the pool");
        } catch (ActionFailed.RequestFailed ex) {
            assertEquals(HttpServletResponse.SC_CONFLICT, ex.getStatusCode());
        }
    }

    @Test
    public void doNotReportWorkloadWhenTheExecutorIsLeavingTheGrid() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        String configRepoUrl = getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);
        j.disableLocalExecutor(gitClient);

        l.record(WorkloadReporter.class, Level.FINE);
        l.capture(5);

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("solaris11"));
        p.scheduleBuild2(0);
        Thread.sleep(100);
        assertEquals(1, j.jenkins.getQueue().countBuildableItems());

        j.reportWorkloadToOrchestrator();

        assertThat(l.getMessages(), contains("Skipping cloud " + cloud.name + " as it is not declared in config repo: " + configRepoUrl));
        assertThat(j.getActiveReservations(), Matchers.<ReservationTask.ReservationExecutable>emptyIterable());
        assertThat(j.getQueuedReservations(), Matchers.<ReservationTask>emptyIterable());
    }

    @Test
    public void cancelPendingReservations() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        String configRepoUrl = getInstance().getConfigRepoUrl();
        j.addSharedNodeCloud(configRepoUrl);

        BlockingBuilder active = j.getBlockingProject("solaris11");
        QueueTaskFuture<FreeStyleBuild> blocked = active.getProject().scheduleBuild2(0);
        active.start.block();
        FreeStyleProject pending = j.createFreeStyleProject();
        pending.setAssignedLabel(Label.get("solaris11"));
        QueueTaskFuture<FreeStyleBuild> queued = pending.scheduleBuild2(0);
        Thread.sleep(100);
        assertFalse(queued.getStartCondition().isDone());
        j.reportWorkloadToOrchestrator();

        assertEquals(1, j.getActiveReservations().size());
        assertEquals(1, j.getQueuedReservations().size());

        j.disableLocalExecutor(gitClient);

        assertEquals(1, j.getActiveReservations().size());
        assertEquals(0, j.getQueuedReservations().size());

        active.end.signal();
        j.assertBuildStatusSuccess(blocked.get());
        assertFalse(queued.getStartCondition().isDone());
    }
}

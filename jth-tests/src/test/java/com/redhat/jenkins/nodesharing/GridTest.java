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

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.Build;
import com.offbytwo.jenkins.model.BuildResult;
import com.offbytwo.jenkins.model.BuildWithDetails;
import com.offbytwo.jenkins.model.Job;
import com.offbytwo.jenkins.model.JobWithDetails;
import com.offbytwo.jenkins.model.QueueItem;
import com.offbytwo.jenkins.model.QueueReference;
import com.redhat.jenkins.nodesharing.utils.ExternalFixture;
import com.redhat.jenkins.nodesharing.utils.ExternalJenkinsRule;
import com.redhat.jenkins.nodesharing.utils.GridRule;
import com.redhat.jenkins.nodesharing.utils.GridRule.Executor;
import com.redhat.jenkins.nodesharing.utils.GridRule.Orchestrator;
import hudson.FilePath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GridTest {

    public @Rule TemporaryFolder tmp = new TemporaryFolder();
    public @Rule GridRule jcr = new GridRule(tmp);

    @Test
    @ExternalFixture(name = "o",  roles = Orchestrator.class, resource = "orchestrator.yaml",   injectPlugins = "matrix-auth")
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = {"matrix-auth", "matrix-project"})
    @ExternalFixture(name = "e1", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = {"matrix-auth", "matrix-project"})
    @ExternalFixture(name = "e2", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = {"matrix-auth", "matrix-project"})
    public void smoke() throws Exception {
        ExternalJenkinsRule.Fixture o = jcr.fixture("o");
        ExternalJenkinsRule.Fixture e0 = jcr.fixture("e0");
        ExternalJenkinsRule.Fixture e1 = jcr.fixture("e1");
        ExternalJenkinsRule.Fixture e2 = jcr.fixture("e2");

        for (ExternalJenkinsRule.Fixture fixture : Arrays.asList(e0, e1, e2)) {
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(10000);
                    System.out.println('.');
                    verifyBuildHasRun(fixture, "sol", "win");
                } catch (AssertionError ex) {
                    if (i == 4) throw ex;
                    // Retry
                }
            }
        }
    }

    private void verifyBuildHasRun(ExternalJenkinsRule.Fixture executor, String... jobNames) throws IOException {
        JenkinsServer jenkinsServer = executor.getClient();
        Map<String, Job> jobs = jenkinsServer.getJobs();
        for (String jobName : jobNames) {
            JobWithDetails job = jobs.get(jobName).details();
            assertThat(job.getNextBuildNumber(), greaterThanOrEqualTo(2));
            Build solBuild = job.getLastFailedBuild();
            if (solBuild != Build.BUILD_HAS_NEVER_RUN) {
                fail("All builds of " + jobName + " succeeded on " + executor.getUri() + ":\n" + solBuild.details().getConsoleOutputText());
            }
        }
    }

    @Test
    @ExternalFixture(name = "o",  roles = Orchestrator.class, resource = "orchestrator.yaml",                 injectPlugins = "matrix-auth")
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-restartOrchestrator.yaml", injectPlugins = "matrix-auth")
    public void restartOrchestrator() throws Exception {
        ExternalJenkinsRule.Fixture e0 = jcr.fixture("e0");
        JenkinsServer executorClient = e0.getClient("admin", "admin");
        JobWithDetails job = executorClient.getJob("running");
        FileBuildBlocker runningBlocker = new FileBuildBlocker(tmp);
        BuildWithDetails running = triggerJobAndWaitUntilStarted(executorClient, "running", job.build(runningBlocker.buildParams()));

        FileBuildBlocker queuedBlocker = new FileBuildBlocker(tmp);
        QueueReference qr = job.build(queuedBlocker.buildParams());
        executorClient.getQueueItem(qr);

        job = executorClient.getJob("running");
        assertTrue(running.isBuilding());
        assertTrue(job.isInQueue());

        JenkinsServer orchestratorClient = jcr.fixture("o").getClient("admin", "admin");

        // Wait until restarted
        orchestratorClient.restart(false);
        while(!orchestratorClient.isRunning()) {
            Thread.sleep(1000);
        }

        runningBlocker.complete();
        do {
            Thread.sleep(1000);
            job = executorClient.getJob("running");
        } while (job.isInQueue());

        BuildWithDetails b = job.getBuildByNumber(1).details();
        assertThat(b.getResult(), equalTo(BuildResult.SUCCESS));

        queuedBlocker.complete();
        do {
            Thread.sleep(1000);
            b = job.getBuildByNumber(2).details();
        } while (b.isBuilding());
        assertThat(b.getResult(), equalTo(BuildResult.SUCCESS));
    }

    private BuildWithDetails triggerJobAndWaitUntilStarted(JenkinsServer server, String jobName, QueueReference queueRef) throws IOException, InterruptedException {
        JobWithDetails job;
        job = server.getJob(jobName);
        QueueItem queueItem = server.getQueueItem(queueRef);
        while (!queueItem.isCancelled() && job.isInQueue()) {
            // TODO: May be we should make this configurable?
            Thread.sleep(200);
            job = server.getJob(jobName);
            queueItem = server.getQueueItem(queueRef);
        }

        if (queueItem.isCancelled()) {
            // TODO: Check if this is ok?
            // We will get the details of the last build. NOT of the cancelled
            // build, cause there is no information about that available cause
            // it does not exist.
            BuildWithDetails result = new BuildWithDetails(job.getLastBuild().details());
            // TODO: Should we add more information here?
            result.setResult(BuildResult.CANCELLED);
            return result;
        }

        job = server.getJob(jobName);
        Build lastBuild = job.getLastBuild();
        return lastBuild.details();
    }

    public static final class FileBuildBlocker {

        private final FilePath tempFile;

        public FileBuildBlocker(TemporaryFolder tmp) throws IOException, InterruptedException {
            tempFile = new FilePath(tmp.newFile());
            tempFile.write("Created", "UTF-8");
        }

        public void complete() throws IOException, InterruptedException {
            assertThat(tempFile.readToString(), equalTo("Started\n"));
            tempFile.write("Done", "UTF-8");
        }

        public Map<String, String> buildParams() {
            return Collections.singletonMap("FILENAME", tempFile.getRemote());
        }
    }
}

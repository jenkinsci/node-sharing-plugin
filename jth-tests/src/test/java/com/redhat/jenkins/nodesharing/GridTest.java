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
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class GridTest {

    private static final int TEST_TIMEOUT = 3 * 60 * 1000;

    public @Rule TemporaryFolder tmp = new TemporaryFolder();
    public @Rule GridRule jcr = new GridRule(tmp);

    @Test(timeout = TEST_TIMEOUT)
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = "matrix-auth")
    @ExternalFixture(name = "e1", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = "matrix-auth")
    @ExternalFixture(name = "e2", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = "matrix-auth")
    @ExternalFixture(name = "o",  roles = Orchestrator.class, resource = "orchestrator.yaml",   injectPlugins = "matrix-auth")
    public void smoke() throws Exception {
        ExternalJenkinsRule.Fixture e0 = jcr.fixture("e0");
        ExternalJenkinsRule.Fixture e1 = jcr.fixture("e1");
        ExternalJenkinsRule.Fixture e2 = jcr.fixture("e2");
        jcr.fixture("o"); // Wait for orchestrator to get up

        for (ExternalJenkinsRule.Fixture fixture : Arrays.asList(e0, e1, e2)) {
            for (int i = 0; ; i++) {
                try {
                    Thread.sleep(10000);
                    System.out.println('.');
                    verifyBuildHasRun(fixture, "sol", "win");
                    return;
                } catch (AssertionError ex) {
                    if (i == 4) {
                        TimeoutException tex = new TimeoutException("Build not completed in time");
                        tex.initCause(ex);
                        throw tex;
                    }
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
            Build lastBuild = job.getLastBuild();
            assertNotNull(lastBuild);
            assertEquals(BuildResult.SUCCESS, lastBuild.details().getResult());
        }
    }

    @Test(timeout = TEST_TIMEOUT)
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-restartOrchestrator.yaml", injectPlugins = "matrix-auth")
    @ExternalFixture(name = "o",  roles = Orchestrator.class, resource = "orchestrator.yaml",                 injectPlugins = "matrix-auth")
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

        ExternalJenkinsRule.Fixture o = jcr.fixture("o");
        JenkinsServer orchestratorClient = o.getClient("admin", "admin");

        // Wait until restarted
        orchestratorClient.restart(false);
        // Reservation verifier needs RestEndpoint#TIMEOUT * 2 to recover the state so this is going to take a while
        await(80000, orchestratorClient::isRunning, throwable -> {
            dumpFixtureLog(o);
            return "Orchestrator have not started responding in time after restart";
        });

        job = executorClient.getJob("running");
        assertTrue(job.isInQueue());
        assertTrue(buildDetails(job, 1).isBuilding());
        runningBlocker.complete();
        await(10000,
                () -> buildDetails(executorClient.getJob("running"), 1).getResult() == BuildResult.SUCCESS,
                throwable -> "Build not completed in time: " +  buildDetails(executorClient.getJob("running"), 1).getResult()
        );

        await(30000, () -> buildDetails(executorClient.getJob("running"), 2).isBuilding(), throwable -> "Build not started in time");

        queuedBlocker.complete();
        await(10000, () -> buildDetails(executorClient.getJob("running"), 2).getResult() == BuildResult.SUCCESS, throwable -> "Build not completed in time");
    }

    private BuildWithDetails buildDetails(JobWithDetails running, int i) throws IOException {
        return running.getBuildByNumber(i).details();
    }

    private void dumpFixtureLog(ExternalJenkinsRule.Fixture o) {
        try {
            System.err.println("Orchestrator ouput:");
            o.getLog().copyTo(System.err);
            System.out.println("===");
        } catch (IOException e) {
            throw new Error(e);
        } catch (InterruptedException e) {
            // Do not throw away the interrupted bit
            Thread.currentThread().interrupt();
        }
    }

    // From JenkinsTriggerHelper
    private BuildWithDetails triggerJobAndWaitUntilStarted(JenkinsServer server, String jobName, QueueReference queueRef) throws IOException, InterruptedException {
        JobWithDetails job;
        job = server.getJob(jobName);
        QueueItem queueItem = server.getQueueItem(queueRef);
        while (!queueItem.isCancelled() && job.isInQueue()) {
            Thread.sleep(200);
            job = server.getJob(jobName);
            queueItem = server.getQueueItem(queueRef);
        }

        if (queueItem.isCancelled()) {
            // We will get the details of the last build. NOT of the cancelled
            // build, cause there is no information about that available cause
            // it does not exist.
            BuildWithDetails result = new BuildWithDetails(job.getLastBuild().details());
            result.setResult(BuildResult.CANCELLED);
            return result;
        }

        job = server.getJob(jobName);
        Build lastBuild = job.getLastBuild();
        return lastBuild.details();
    }

    private void await(int milliseconds, Callable<Boolean> until, OnTimeoutHandler<Throwable, String> onTimeout) throws Exception {
        long end = System.currentTimeMillis() + milliseconds;
        long step = milliseconds / 10;

        Throwable last = null;
        for(;;) {
            try {
                Boolean call = until.call();
                if (Boolean.TRUE.equals(call)) return;
            } catch (InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                last = ex;
            }

            if (System.currentTimeMillis() + step > end) break;

            Thread.sleep(step);
        }

        String diagnosis = onTimeout.act(last);
        TimeoutException timeoutException = new TimeoutException(diagnosis);
        timeoutException.initCause(last);
        throw timeoutException;
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

    @FunctionalInterface
    private interface OnTimeoutHandler<Arg, Ret> {
        Ret act(Arg arg) throws Exception;
    }
}

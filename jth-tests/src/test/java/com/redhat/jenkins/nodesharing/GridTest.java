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
import com.redhat.jenkins.nodesharing.utils.SlowTest;
import hudson.FilePath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@Category(SlowTest.class)
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
                    if (i == 8) {
                        TimeoutException tex = new TimeoutException("Build not completed in time");
                        tex.initCause(ex);
                        throw tex;
                    }
                    // Retry
                }
            }
        }
    }

    @Test(timeout = TEST_TIMEOUT)
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-smoke.yaml",          injectPlugins = "matrix-auth")
    @ExternalFixture(name = "e1", roles = Executor.class,     resource = "executor-smoke.yaml",          injectPlugins = "matrix-auth")
    @ExternalFixture(name = "e2", roles = Executor.class,     resource = "executor-smoke.yaml",          injectPlugins = "matrix-auth")
    @ExternalFixture(name = "o",  roles = Orchestrator.class, resource = "orchestrator-credential.yaml", injectPlugins = "matrix-auth", setupEnvCredential = false, credentialId = "rest-cred-id")
    public void smokeNoEnvCredential() throws Exception {
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
                    if (i == 8) {
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

    @Test(timeout = TEST_TIMEOUT * 2)
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-restartOrchestrator.yaml", injectPlugins = "matrix-auth")
    @ExternalFixture(name = "o",  roles = Orchestrator.class, resource = "orchestrator.yaml",                 injectPlugins = "matrix-auth")
    public void restartOrchestrator() throws Exception {
        ExternalJenkinsRule.Fixture e0 = jcr.fixture("e0");
        JenkinsServer executorClient = e0.getClient("admin", "admin");
        JobWithDetails job = executorClient.getJob("running");

        // Run nr. 1 - will be in building state during Orchestrator restart
        FileBuildBlocker runningBlocker = new FileBuildBlocker(tmp);
        BuildWithDetails running = triggerJobAndWaitUntilStarted(executorClient, "running",
                job.build(runningBlocker.buildParams()));
        await(10000,
                () -> runningBlocker.isRunning(),
                throwable -> { dumpFixtureLogs(); return "Build not running in time";
        });

        // Run nr. 2 - will be in queued state during Orchestrator restart
        FileBuildBlocker queuedBlocker = new FileBuildBlocker(tmp);
        QueueReference qr = job.build(queuedBlocker.buildParams());
        executorClient.getQueueItem(qr);

        job = executorClient.getJob("running");
        // Check whether there are two runs for 'running' job:
        //  - nr. 1. is building already
        //  - nr. 2 sits in the queue before Orchestrator restart
        assertTrue(running.isBuilding());
        assertTrue(job.isInQueue());

        ExternalJenkinsRule.Fixture o = jcr.fixture("o");
        JenkinsServer orchestratorClient = o.getClient("admin", "admin");

        // Wait until restarted
        orchestratorClient.restart(false);
        // Reservation verifier needs RestEndpoint#TIMEOUT * 2 to recover the state so this is going to take a while
        await(60000 * 3, orchestratorClient::isRunning, throwable -> {
            dumpFixtureLog(o);
            return "Orchestrator have not started responding in time after restart";
        });

        job = executorClient.getJob("running");

        // The status for runs of 'running' job should be still the same and has to survive the Orchestrator restart
        assertTrue(job.isInQueue());
        assertTrue(buildDetails(job, 1).isBuilding());

        // Signal to finish run nr. 1
        runningBlocker.complete();

        // Run nr. 1 should complete with success
        await(20000,
                () -> buildDetails(executorClient.getJob("running"), 1).getResult() == BuildResult.SUCCESS,
                throwable -> { dumpFixtureLogs(); return "Build not completed in time"; }
        );

        // Run nr. 2 should be building right after nr. 1 finishes
        await(30000,
                () -> buildDetails(executorClient.getJob("running"), 2).isBuilding(),
                throwable -> { dumpFixtureLogs(); return "Build not started in time";
        });
        await(10000,
                () -> queuedBlocker.isRunning(),
                throwable -> { dumpFixtureLogs(); return "Build not running in time";
        });

        // Signal to finish run nr. 2
        queuedBlocker.complete();

        // Run nr. 2 should complete with success as well
        await(20000, () -> buildDetails(executorClient.getJob("running"), 2).getResult() == BuildResult.SUCCESS, throwable -> { dumpFixtureLogs(); return "Build not completed in time"; });
    }

    @Test(timeout = TEST_TIMEOUT * 2)
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-restartOrchestrator.yaml", injectPlugins = "matrix-auth")
    @ExternalFixture(name = "o1", roles = Orchestrator.class, resource = "orchestrator.yaml",                 injectPlugins = "matrix-auth")
    @ExternalFixture(name = "o2", roles = Orchestrator.class, resource = "orchestrator.yaml",                 injectPlugins = "matrix-auth")
    public void changeOrchestratorUrlSmokeTest() throws Exception {
        ExternalJenkinsRule.Fixture e0 = jcr.fixture("e0");
        JenkinsServer executorClient0 = e0.getClient("admin", "admin");

        ExternalJenkinsRule.Fixture o1 = jcr.fixture("o1");
        JenkinsServer orchestratorClient1 = o1.getClient("admin", "admin");

        ExternalJenkinsRule.Fixture o2 = jcr.fixture("o2");
        JenkinsServer orchestratorClient2 = o1.getClient("admin", "admin");

        JobWithDetails job = executorClient0.getJob("running");

        // Run nr. 1 - will be in building state during Orchestrator restart
        FileBuildBlocker runningBlocker = new FileBuildBlocker(tmp);
        BuildWithDetails running = triggerJobAndWaitUntilStarted(executorClient0, "running",
                job.build(runningBlocker.buildParams()));
        await(10000,
                () -> runningBlocker.isRunning(),
                throwable -> { dumpFixtureLogs(); return "Build not running in time";
        });

        // Run nr. 2 - will be in queued state during Orchestrator restart
        FileBuildBlocker queuedBlocker = new FileBuildBlocker(tmp);
        QueueReference qr = job.build(queuedBlocker.buildParams());
        executorClient0.getQueueItem(qr);

        job = executorClient0.getJob("running");
        // Check whether there are two runs for 'running' job:
        //  - nr. 1. is building already
        //  - nr. 2 sits in the queue before Orchestrator restart
        assertTrue(running.isBuilding());
        assertTrue(job.isInQueue());

        job = executorClient0.getJob("running");

        // The status for runs of 'running' job should be still the same and has to survive the Orchestrator restart
        assertTrue(job.isInQueue());
        assertTrue(buildDetails(job, 1).isBuilding());

        FilePath config = jcr.configRepo().getWorkTree().child("config");
        assertThat(config.readToString(), containsString("orchestrator.url=" + o2.getUri()));
        config.write(config.readToString().replace(o2.getUri().toString(), o1.getUri().toString()),"UTF-8");
        jcr.configRepo().add("config");
        jcr.configRepo().commit("Writing a new Orchestrator URL");
        assertThat(config.readToString(), containsString("orchestrator.url=" + o1.getUri()));

        // Make sure updated config is propagated through the grid
        executorClient0.runScript("Jenkins.instance.getExtensionList(com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud.ConfigRepoUpdater.class).get(0).doRun();");
        assertThat(executorClient0.runScript(
                "com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud.getAll().get(0).getLatestConfig().getOrchestratorUrl();"),
                equalTo("Result: " + o1.getUri() + "\n")
        );
        orchestratorClient1.runScript("com.redhat.jenkins.nodesharingbackend.Pool.Updater.getInstance().doRun();");
        assertThat(orchestratorClient1.runScript(
                "com.redhat.jenkins.nodesharingbackend.Pool.getInstance().getConfig().getOrchestratorUrl();"),
                equalTo("Result: " + o1.getUri() + "\n")
        );
        orchestratorClient2.runScript("com.redhat.jenkins.nodesharingbackend.Pool.Updater.getInstance().doRun();");
        assertThat(orchestratorClient2.runScript(
                "com.redhat.jenkins.nodesharingbackend.Pool.getInstance().getConfig().getOrchestratorUrl();"),
                equalTo("Result: " + o1.getUri() + "\n")
        );

        // Signal to finish run nr. 1
        runningBlocker.complete();

        // Run nr. 1 should complete with success
        await(20000,
                () -> buildDetails(executorClient0.getJob("running"), 1).getResult() == BuildResult.SUCCESS,
                throwable -> { dumpFixtureLogs(); return "Build not completed in time";
        });

        // Run nr. 2 should be building right after nr. 1 finishes
        await(30000,
                () -> buildDetails(executorClient0.getJob("running"), 2).isBuilding(),
                throwable -> { dumpFixtureLogs(); return "Build not started in time";
        });
        await(10000,
                () -> queuedBlocker.isRunning(),
                throwable -> { dumpFixtureLogs(); return "Build not running in time";
        });

        // Signal to finish run nr. 2
        queuedBlocker.complete();

        // Run nr. 2 should complete with success as well
        await(20000,
                () -> buildDetails(executorClient0.getJob("running"), 2).getResult() == BuildResult.SUCCESS,
                throwable -> { dumpFixtureLogs(); return "Build not completed in time";
        });

        // Wait a bit for node termination
        Thread.sleep(1000);

        String e0Log = e0.getLog().readToString();
        // Executor should terminate computer twice
        assertThat(e0Log, matchesPattern(
                "(.+\n)+"
                + "INFO: Terminating computer solaris1.acme.com-NodeSharing-.+(.+\n)*"
                + "INFO: Terminating computer solaris1.acme.com-NodeSharing-.+(.+\n)*")
        );

        String o1Log = o1.getLog().readToString();
        // Orchestrator 1 should register one release attempt before it knows that it was reserved,
        // second reservation should be processed like common case
        assertThat(o1Log, matchesPattern(
                "(.*\n)*INFO: An attempt to return a node 'solaris1.acme.com' that is not reserved by " + e0.getUri()+"(.*\n)*"
                        +"INFO: Reservation of solaris1.acme.com by e0 .+ completed(.*\n)*")
        );

        String o2Log = o2.getLog().readToString();
        // Orchestrator 2 should register one reservation without release
        assertThat(o2Log, matchesPattern(
                "(.*\n)*INFO: Reservation of solaris1.acme.com by e0 .+ started(.*\n)*")
        );
        // Orchestrator 2 shouldn't register two reservations
        assertThat(o2Log, matchesPattern(
                "(.*\n)*(?!INFO: Reservation of solaris1.acme.com by e0 .+ started(.*\n)*INFO: Reservation of solaris1.acme.com by e0 .+ started)(.*\n)*")
        );
        // Orchestrator 2 shouldn't register any release attempt
        assertThat(o2Log, matchesPattern(
                "(.*\n)*(?!INFO: Reservation of solaris1.acme.com by e0 .+ completed)(.*\n)*")
        );
    }

    private BuildWithDetails buildDetails(JobWithDetails running, int i) throws IOException {
        return running.getBuildByNumber(i).details();
    }

    private void dumpFixtureLogs() throws ExecutionException, InterruptedException {
        for (ExternalJenkinsRule.Fixture fixture : jcr.getFixtures().values()) {
            dumpFixtureLog(fixture);
        }
    }

    private void dumpFixtureLog(ExternalJenkinsRule.Fixture o) {
        try {
            System.err.println("\n" + o.getAnnotation().name() + " output:");
            // FilePath.copyTo(System.err) trunks the log content somehow
            System.err.println(o.getLog().readToString());
            System.err.println("===");
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

        String timing = "(Waited " + milliseconds + "ms until " + new Date() + ")"; // Call ASAP after timeout
        String diagnosis = onTimeout.act(last) + timing;
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

        public boolean isRunning() throws IOException, InterruptedException {
            return tempFile.readToString().equals("Started\n");
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

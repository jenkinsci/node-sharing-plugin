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
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ShareableComputer;
import hudson.FilePath;
import hudson.matrix.AxisList;
import hudson.matrix.LabelExpAxis;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.Executor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import hudson.tasks.Shell;
import hudson.triggers.TimerTrigger;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class GridTest {

    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();
    @Rule public ExternalGridRule grid = new ExternalGridRule(j);

    @Test
    public void restartOrchestrator() throws Exception {
        GitClient repo = grid.masterGrid(j.jenkins);

        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "p");
        FileBuildBlocker blocker = new FileBuildBlocker();
        p.getBuildersList().add(blocker.getShellStep());
        p.setAssignedLabel(Label.get("solaris11"));
        URL executorUrl = grid.executor(repo).get();
        p.delete();

        Pool.Updater.getInstance().doRun();
        assertEquals(1, Pool.getInstance().getConfig().getJenkinses().size());

        // When the build is running and one more is queued
        JenkinsServer exec = new JenkinsServer(executorUrl.toURI(), "admin", "admin");
        exec.getJob("p").build();

        Build build1 = waitForBuildStarted(exec, "p", 1);
        exec.getJob("p").build();

        ShareableComputer computer = j.getComputer("solaris1.acme.com");
        while (computer.isIdle()) { // until queue is propagated and build scheduled
            Thread.sleep(1000);
        }

        // It should pick the state up from executors
        assertNotNull("Build in progress on orchestrator side: " + ShareableComputer.getAllReservations(), computer.getReservation());

        // When orchestrator is restarted
        // TODO use real restart here
        System.out.println("Simulating restart");
        j.jenkins.getQueue().clear();
        for (Node node : j.jenkins.getNodes()) {
            for (Executor executor : node.toComputer().getExecutors()) {
                executor.interrupt(Result.ABORTED);
            }
            j.jenkins.removeNode(node);
        }
        Pool.ensureOrchestratorIsUpToDateWithTheGrid();
        assertThat(Pool.ADMIN_MONITOR.getErrors().values(), Matchers.emptyIterable());
        Thread.sleep(1000);
        computer = j.getComputer("solaris1.acme.com");

        // Then it should pick the state up from executors
        assertNotNull("Build was not rescheduled by ReservationVerifier: " + ShareableComputer.getAllReservations(), computer.getReservation());

        blocker.complete();
        BuildWithDetails build1details = waitForBuildComplete(build1);
        assertEquals(BuildResult.SUCCESS, build1details.getResult());

        Build build2 = waitForBuildStarted(exec, "p", 2);
        assertEquals(1, j.getActiveReservations().size());
        blocker.complete();
        BuildWithDetails build2details = waitForBuildComplete(build2);
        assertEquals(BuildResult.SUCCESS, build2details.getResult());

        assertThat(j.getActiveReservations(), Matchers.<ReservationTask.ReservationExecutable>emptyIterable());
        assertThat(j.getQueuedReservations(), Matchers.<ReservationTask>emptyIterable());
    }

    private @Nonnull BuildWithDetails waitForBuildComplete(Build build) throws IOException, InterruptedException {
        BuildWithDetails details = build.details();
        while (details.isBuilding()) {
            Thread.sleep(2000);
            details = build.details();
        }
        return details;
    }

    private Build waitForBuildStarted(JenkinsServer jenkins, String job, int number) throws Exception {
        Build build = null;
        for (;;) {
            if (build == null) {
                build = jenkins.getJob(job).getBuildByNumber(number);
            }
            if (build != null && build.details().isBuilding()) {
                return build;
            }
            Thread.sleep(500);
        }
    }

    /**
     * Create shell build step that can be completed by updating a file.
     */
    public static final class FileBuildBlocker {

        private final FilePath tempFile;

        public FileBuildBlocker() throws IOException, InterruptedException {
            tempFile = new FilePath(File.createTempFile("node-sharing", getClass().getSimpleName()));
            tempFile.write("Created", "UTF-8");
        }

        public Shell getShellStep() {
            return new Shell(
                    "#!/usr/bin/env bash -x\n" + // Ensure bash is used in case it would not be the default shell
                    "echo 'Started' > '" + tempFile.getRemote() + "'\n" +
                    "while true; do\n" +
                    "  if [ \"$(cat '" + tempFile.getRemote() + "')\" == 'Done' ]; then\n" +
                    "    rm '" + tempFile.getRemote() + "'\n" +
                    "    exit 0\n" +
                    "  fi\n" +
                    "  sleep 1\n" +
                    "done\n"
            );
        }

        public void complete() throws IOException, InterruptedException {
            assertThat(tempFile.readToString(), equalTo("Started\n"));
            tempFile.write("Done", "UTF-8");
        }
    }
}

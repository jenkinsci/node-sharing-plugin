package com.redhat.jenkins.nodesharing;

import com.google.common.collect.Sets;
import com.redhat.jenkins.nodesharing.transport.ExecutorEntity;
import com.redhat.jenkins.nodesharing.transport.ReportUsageResponse;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharing.utils.DoNotSquashQueueAction;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ReservationVerifier;
import com.redhat.jenkins.nodesharingbackend.ShareableComputer;
import com.redhat.jenkins.nodesharingbackend.ShareableNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.TestBuilder;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.redhat.jenkins.nodesharingbackend.Pool.getInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ReservationVerifierTest {

    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();
    @Rule public LoggerRule l = new LoggerRule();

    private Pool pool;
    private SharedNodeCloud cloud;

    @Before
    public void setUp() throws Exception {
        l.record(Logger.getLogger(ReservationVerifier.class.getName()), Level.INFO);
        l.capture(10);

        j.singleJvmGrid(j.jenkins);
        pool = getInstance();
        cloud = j.addSharedNodeCloud(pool.getConfigRepoUrl());
    }

    @Test // Case: A1, A2
    public void stress() throws Exception {
        FreeStyleProject project = j.createProject(FreeStyleProject.class);
        project.setAssignedLabel(Label.get("windows||solaris"));
        project.setConcurrentBuild(true);
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
                int delay = build.number % 10;
                Thread.sleep(delay * 1000);
                return true;
            }
        });

        Timer.get().scheduleAtFixedRate(new ReservationVerifier(), 0, 1, TimeUnit.SECONDS);

        for (int i = 0; i < 10; i++) {
            project.scheduleBuild2(0, new DoNotSquashQueueAction());
            Thread.sleep(500);
        }

        j.waitUntilNoActivity();
        for (FreeStyleBuild build : project.getBuilds()) {
            j.assertBuildStatusSuccess(build);
        }

        assertThat(l, notLogged(Level.WARNING, ".*"));
        assertThat(l, notLogged(Level.INFO, ".*"));
    }

    @Test // Case: A1, A2 (no collisions intended here - though the rapid scheduling approach invokes race conditions that resembled collisions to ReservationVErifier)
    // TODO extend timeout and add more iterations after https://issues.jenkins-ci.org/browse/JENKINS-49046 is fixed
    public void stress2() throws Exception {
        FreeStyleProject project = j.createProject(FreeStyleProject.class);
        project.setAssignedLabel(Label.get("windows||solaris"));
        project.setConcurrentBuild(true);
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                return true;
            }
        });

        Timer.get().scheduleAtFixedRate(new ReservationVerifier(), 0, 1, TimeUnit.SECONDS);

        for (int i = 0; i < 40; i++) {
            project.scheduleBuild2(0, new DoNotSquashQueueAction());
        }

        j.waitUntilNoActivity();
        for (FreeStyleBuild build : project.getBuilds()) {
            j.assertBuildStatusSuccess(build);
        }

        assertThat(l, notLogged(Level.WARNING, ".*"));
        assertThat(l, notLogged(Level.INFO, ".*"));
    }

    @Test // Case: NC1
    public void completeReservationWhenNotUsedOnExecutor() throws Exception {
        ExecutorJenkins executor = getSomeExecutor(pool);
        ShareableNode node = getSomeShareableNode();

        startDanglingReservation(executor, node);

        ReservationVerifier.getInstance().doRun();

        assertThat(l, notLogged(Level.WARNING, ".*"));
        assertThat(l, logged(Level.INFO, "Cancelling dangling reservation for " + node.getNodeName() + " and " + executor.getName()));
        Thread.sleep(1000);
        assertNull(node.getComputer().getReservation());
    }

    @Test // Case: NC2
    public void createBackfillWhenReservationNotTrackedOnOrchestrator() throws Exception {
        ShareableNode shareableNode = getSomeShareableNode();
        assertNotNull(cloud.getLatestConfig());
        SharedNode sharedNode = cloud.createNode(shareableNode.getNodeDefinition());

        FreeStyleProject p = j.createProject(FreeStyleProject.class);
        p.setAssignedNode(sharedNode);
        BlockingBuilder bb = new BlockingBuilder();
        p.getBuildersList().add(bb);
        QueueTaskFuture<FreeStyleBuild> fb = p.scheduleBuild2(0);

        Jenkins.getActiveInstance().addNode(sharedNode);
        FreeStyleBuild build = fb.getStartCondition().get();
        bb.start.block();

        assertNull(shareableNode.getComputer().getReservation());
        ReservationVerifier.getInstance().doRun();
        Thread.sleep(5000);
        assertNotNull(ShareableComputer.getAllReservations().toString(), shareableNode.getComputer().getReservation());

        bb.end.signal();
        fb.get();
        j.assertBuildStatusSuccess(build);

        assertThat(l, notLogged(Level.WARNING, ".*"));
        assertThat(l, logged(Level.INFO, "Starting backfill reservation for " + shareableNode.getNodeName() + ".*"));

        j.waitUntilNoActivity();
    }

    private void startDanglingReservation(ExecutorJenkins executor, ShareableNode node) throws InterruptedException, java.util.concurrent.ExecutionException {
        assertNull(node.getComputer().getReservation());
        new ReservationTask(executor, node.getNodeName(), true).schedule().getFuture().getStartCondition().get();
        assertNotNull(node.getComputer().getReservation());
    }

    private ShareableNode getSomeShareableNode() {
        return ShareableNode.getAll().values().iterator().next();
    }

    private ExecutorJenkins getSomeExecutor(Pool pool) {
        return pool.getConfig().getJenkinses().iterator().next();
    }

    private static TypeSafeDiagnosingMatcher<LoggerRule> logged(final Level level, final String pattern) {
        return new HasLogged(level, pattern, true);
    }

    // Negating TypeSafeDiagnosingMatcher does not seem to print diagnosis making it quite useless
    private static TypeSafeDiagnosingMatcher<LoggerRule> notLogged(final Level level, final String pattern) {
        return new HasLogged(level, pattern, false);
    }

    private static class HasLogged extends TypeSafeDiagnosingMatcher<LoggerRule> {
        private final Level level;
        private final String pattern;
        private final boolean positiveMatch;

        public HasLogged(Level level, String pattern, boolean positiveMatch) {
            this.level = level;
            this.pattern = pattern;
            this.positiveMatch = positiveMatch;
        }

        @Override public void describeTo(Description description) {
            if (!positiveMatch) description.appendText("not ");
            description.appendText("logged message on level " + level + " matching '" + pattern + "'");
        }

        @Override protected boolean matchesSafely(LoggerRule lr, Description md) {
            ArrayList<String> mismatched = new ArrayList<>();
            ArrayList<String> matched = new ArrayList<>();
            for (LogRecord logRecord : lr.getRecords()) {
                Level lvl = logRecord.getLevel();
                if (!lvl.equals(level)) continue;

                String msg = logRecord.getMessage();
                if (msg.matches(pattern)) {
                    matched.add(lvl + ": " + msg);
                } else {
                    mismatched.add(lvl + ": " + msg);
                }
            }

            boolean found = !matched.isEmpty();
            if (found == positiveMatch) return true;

            reportFound(md, positiveMatch ? mismatched : matched);
            return false;
        }

        private void reportFound(Description md, ArrayList<String> mismatched) {
            md.appendText("found logs: ");
            for (String s : mismatched) {
                md.appendText(s).appendText("; ");
            }
            md.appendText(".");
        }
    }
}

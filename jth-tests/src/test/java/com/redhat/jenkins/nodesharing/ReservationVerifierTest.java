package com.redhat.jenkins.nodesharing;

import com.redhat.jenkins.nodesharing.transport.ExecutorEntity;
import com.redhat.jenkins.nodesharing.transport.ReportUsageResponse;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharing.utils.DoNotSquashQueueAction;
import com.redhat.jenkins.nodesharing.utils.NodeSharingJenkinsRule;
import com.redhat.jenkins.nodesharing.utils.TestUtils;
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
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.recipes.WithTimeout;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.redhat.jenkins.nodesharingbackend.Pool.getInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReservationVerifierTest {

    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();
    @Rule public LoggerRule l = new LoggerRule();

    private Pool pool;
    private SharedNodeCloud cloud;
    private GitClient gitClient;

    @Before
    public void setUp() throws Exception {
        l.record(Logger.getLogger(ReservationVerifier.class.getName()), Level.INFO);
        l.capture(10);

        gitClient = j.singleJvmGrid(j.jenkins);
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
    }

    @Test @WithTimeout(300) // Case: A1, A2
    // no collisions intended here - though the rapid scheduling approach invokes race conditions that resembles collisions to ReservationVerifier
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
    }

    @Test // Case: NC1
    public void completeReservationWhenNotUsedOnExecutor() throws Exception {
        ExecutorJenkins executor = j.getSomeExecutor();
        ShareableNode node = j.getSomeShareableNode();

        j.startDanglingReservation(executor, node);

        // Executor will report no node usage
        Api api = mock(Api.class);
        when(api.reportUsage(Mockito.any(ExecutorJenkins.class))).thenReturn(new ReportUsageResponse(
                new ExecutorEntity.Fingerprint(pool.getConfigRepoUrl(), "7", executor.getUrl().toExternalForm()),
                Collections.<String>emptyList()
        ));

        ReservationVerifier.verify(pool.getConfig(), api);
        Thread.sleep(1000);

        assertThat(l, notLogged(Level.WARNING, ".*"));
        assertThat(l, logged(Level.INFO, "Canceling dangling Reservation of " + node.getNodeName() + " by " + executor.getName() + ".*"));
        assertNull(node.getComputer().getReservation());
        j.waitUntilNoActivity();
    }

    @Test // Case: NC2
    public void createBackfillWhenReservationNotTrackedOnOrchestrator() throws Exception {
        ShareableNode shareableNode = j.getSomeShareableNode();
        assertNotNull(cloud.getLatestConfig());
        SharedNode sharedNode = cloud.createNode(shareableNode.getNodeDefinition());

        BlockingBuilder bb = j.getBlockingProject(sharedNode);
        QueueTaskFuture<FreeStyleBuild> fb = bb.getProject().scheduleBuild2(0);

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
        assertThat(l, logged(Level.INFO, "Starting backfill Reservation '" + shareableNode.getNodeName() + "'.*"));

        j.waitUntilNoActivity();
    }

    @Test // Case: NC3
    // Orchestrator believes that 'A' has 'a' and `B` has 'b' but `A` reports to have 'b' and 'B' reports to have 'a'
    public void resolveCyclicReservation() throws Exception {
        // Given
        Map<String, String> jenkinses = new HashMap<>();
        jenkinses.put("A", "https://A.com/");
        jenkinses.put("B", "http://B.com");
        TestUtils.declareExecutors(gitClient, jenkinses);
        ConfigRepo.Snapshot config = cloud.getLatestConfig();

        ExecutorJenkins A = config.getJenkinsByName("A");
        ExecutorJenkins B = config.getJenkinsByName("B");
        Iterator<ShareableNode> nodes = ShareableNode.getAll().values().iterator();
        ShareableNode a = nodes.next();
        ShareableNode b = nodes.next();

        Api api = mock(Api.class);
        ExecutorEntity.Fingerprint Afingerprint = new ExecutorEntity.Fingerprint("git://config.com/repo.git", "4.2", A.getUrl().toExternalForm());
        when(api.reportUsage(eq(A))).thenReturn(new ReportUsageResponse(Afingerprint, Collections.singletonList(b.getNodeName())));
        ExecutorEntity.Fingerprint Bfingerprint = new ExecutorEntity.Fingerprint("git://config.com/repo.git", "4.2", B.getUrl().toExternalForm());
        when(api.reportUsage(eq(B))).thenReturn(new ReportUsageResponse(Bfingerprint, Collections.singletonList(a.getNodeName())));

        j.startDanglingReservation(A, a);
        j.startDanglingReservation(B, b);

        Map<ShareableComputer, ReservationTask.ReservationExecutable> actual = ShareableComputer.getAllReservations();
        assertEquals(A, actual.get(a.toComputer()).getParent().getOwner());
        assertEquals(B, actual.get(b.toComputer()).getParent().getOwner());

        // When
        ReservationVerifier.verify(config, api);
        Thread.sleep(3000);

        // Then
        assertThat(j.getQueuedReservations(), emptyIterable());
        actual = ShareableComputer.getAllReservations();

        assertEquals(A, actual.get(b.toComputer()).getParent().getOwner());
        assertEquals(B, actual.get(a.toComputer()).getParent().getOwner());
        assertThat(j.getActiveReservations().size(), equalTo(2));

        assertThat(l, notLogged(Level.WARNING, ".*"));
        assertThat(l, logged(Level.INFO, "Canceling dangling Reservation of " + a.getNodeName() + " by A .*"));
        assertThat(l, logged(Level.INFO, "Canceling dangling Reservation of " + b.getNodeName() + " by B .*"));
        assertThat(l, logged(Level.INFO, "Starting backfill Reservation '" + b.getNodeName() + "' by A .*"));
        assertThat(l, logged(Level.INFO, "Starting backfill Reservation '" + a.getNodeName() + "' by B .*"));

        // Cleanup
        for (ReservationTask.ReservationExecutable ar : j.getActiveReservations()) {
            ar.complete();
        }
        j.waitUntilNoActivity();
    }

    public static TypeSafeDiagnosingMatcher<LoggerRule> logged(final Level level, final String pattern) {
        return new HasLogged(level, pattern, true);
    }

    // Negating TypeSafeDiagnosingMatcher does not seem to print diagnosis making it quite useless
    public static TypeSafeDiagnosingMatcher<LoggerRule> notLogged(final Level level, final String pattern) {
        return new HasLogged(level, pattern, false);
    }

    public static class HasLogged extends TypeSafeDiagnosingMatcher<LoggerRule> {
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

package com.redhat.jenkins.nodesharingbackend;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.ScheduleResult;
import hudson.slaves.DumbSlave;
import hudson.util.OneShotEvent;
import hudson.util.StreamTaskListener;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.util.Timer;
import org.apache.commons.io.FileUtils;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.redhat.jenkins.nodesharingbackend.Pool.CONFIG_REPO_PROPERTY_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PoolTest {

    private static final ExecutorJenkins DUMMY_OWNER = new ExecutorJenkins("https://jenkins42.acme.com", "jenkins42");

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void inactiveWithNoProperty() throws Exception {
        Pool.Updater.getInstance().doRun();
        Pool pool = Pool.getInstance();
        assertNull(pool.getConfig());
        assertThat(pool.getError().getMessage(), startsWith("Node sharing Config Repo not configured"));
        assertThat(j.jenkins.getNodes(), Matchers.<Node>emptyIterable());
    }

    @Test
    public void readConfigFromRepo() throws Exception {
        injectDummyConfigRepo();
        Pool pool = Pool.getInstance();
        Map<String, String> config = pool.getConfig().getConfig();
        assertEquals("https://dummy.test", config.get("orchestrator.url"));

        assertThat(pool.getConfig().getJenkinses(), containsInAnyOrder(
                new ExecutorJenkins("https://jenkins1.acme.com", "jenkins1"),
                new ExecutorJenkins("https://jenkins2.acme.com", "jenkins2")
        ));

        assertFalse(pool.isActivated());
        assertNull(pool.getError());
    }

    @Test
    public void populateComputers() throws Exception {
        GitClient git = injectDummyConfigRepo();
        Node win1 = getNode("win1.acme.com");
        assertEquals("windows w2k12", win1.getLabelString());
        assertTrue(win1.toComputer().isOnline());

        assertThat(j.jenkins.getComputers(), arrayWithSize(4));

        // Same changes re-applied with no inventory change
        git.getWorkTree().child("fake_change").touch(0);
        git.add("*");
        git.commit("Update"); // New commit is needed to force computer update

        for (int i = 0; i < 2; i++) { // Update with no changes preserves state
            Pool.Updater.getInstance().doRun();

            assertThat(j.jenkins.getComputers(), arrayWithSize(4));
            assertSame(win1, getNode("win1.acme.com"));
            assertSame(win1.toComputer(), getNode("win1.acme.com").toComputer());
        }
    }

    @Test
    public void updateComputers() throws Exception {
        DumbSlave doNotTouchMe = j.createOnlineSlave(); // There is no reason for using some other slave kinds on orchestrator but ...
        GitClient git = injectDummyConfigRepo();

        assertEquals("windows w2k16", getNode("win2.acme.com").getLabelString());
        assertEquals("solaris11 sparc", getNode("solaris1.acme.com").getLabelString());
        assertNull(j.jenkins.getNode("windows.acme.com"));

        Node nodeW1 = getNode("win1.acme.com");
        Computer computerW1 = nodeW1.toComputer();

        // Update
        FilePath workTree = git.getWorkTree().child("nodes");
        workTree.child("win2.acme.com.xml").renameTo(workTree.child("windows.acme.com.xml")); // Technically, we should rename the attribute as well
        FilePath solarisXml = workTree.child("solaris1.acme.com.xml");
        String newConfig = solarisXml.readToString().replace("solaris11", "solaris12");
        solarisXml.write(newConfig, Charset.defaultCharset().name());
        git.add("nodes/*");
        git.commit("Update");
        Pool.Updater.getInstance().doRun();

        assertEquals("windows w2k16", getNode("windows.acme.com").getLabelString());
        assertEquals("solaris12 sparc", getNode("solaris1.acme.com").getLabelString());
        assertNull(j.jenkins.getNode("win2.acme.com"));
        assertNull(j.jenkins.getComputer("win2.acme.com"));
        assertSame(nodeW1, getNode("win1.acme.com"));
        assertSame(computerW1, getNode("win1.acme.com").toComputer());

        assertNotNull(j.jenkins.getNode(doNotTouchMe.getNodeName()));
    }

    @Test
    public void workloadMapping() throws Exception {
        injectDummyConfigRepo();

        MockTask task = new MockTask(DUMMY_OWNER, Label.get("solaris11"));
        Queue.Item item = task.schedule();
        assertEquals("jenkins42", item.task.getFullDisplayName());
        item.getFuture().get();
        assertEquals(getNode("solaris1.acme.com").toComputer(), task.actuallyRunOn[0]);


        task = new MockTask(DUMMY_OWNER, Label.get("windows"));
        task.schedule().getFuture().get();
        assertThat(task.actuallyRunOn[0].getName(), startsWith("win"));

        // Never schedule labels we do not serve - including empty one
        task = new MockTask(DUMMY_OWNER, Label.get(""));
        ScheduleResult scheduleResult = j.jenkins.getQueue().schedule2(task, 0);
        assertTrue(scheduleResult.isAccepted());
        assertFalse(scheduleResult.isRefused());
        Future<Queue.Executable> startCondition = scheduleResult.getItem().getFuture().getStartCondition();
        assertFalse(startCondition.isDone());
        Thread.sleep(1000);
        assertFalse(startCondition.isDone());
    }

    @Test
    public void waitUntilComputerGetsIdleBeforeDeleting() throws Exception {
        final String DELETED_NODE = "solaris1.acme.com";
        GitClient git = injectDummyConfigRepo();

        BlockingTask task = new BlockingTask(Label.get("solaris11"));
        task.schedule();
        task.running.block();
        assertFalse("Computer occupied", getNode(DELETED_NODE).toComputer().isIdle());

        assertTrue(git.getWorkTree().child("nodes").child(DELETED_NODE + ".xml").delete());
        git.add("*");
        git.commit("Remove running node from config repo");
        Pool.Updater.getInstance().doRun();

        assertFalse("Node still exists and occupied", getNode(DELETED_NODE).toComputer().isIdle());
        Thread.sleep(1000); // It is not an accident
        Pool.Updater.getInstance().doRun(); // Trigger the check
        assertFalse("Node still exists and occupied", getNode(DELETED_NODE).toComputer().isIdle());

        task.done.signal();
        j.waitUntilNoActivity();
        Pool.Updater.getInstance().doRun(); // Trigger the check
        assertNull("Node removed", j.jenkins.getNode(DELETED_NODE));
        assertNull("Computer removed", j.jenkins.getComputer(DELETED_NODE));
    }

    @Test
    public void brokenConfig() throws Exception {
        Pool pool = Pool.getInstance();
        Pool.Updater updater = Pool.Updater.getInstance();

        GitClient cr = injectDummyConfigRepo();
        cr.getWorkTree().child("config").write("No orchestrator url here", "cp1250" /*muahaha*/);
        cr.add("*");
        cr.commit("Break it!");
        updater.doRun();
        assertThat(pool.getError().getMessage(), startsWith("No orchestrator.url specified by Config Repository"));
        assertTrue(pool.isActivated());

        cr = injectDummyConfigRepo();
        cr.getWorkTree().child("config").delete();
        cr.add("*");
        cr.commit("Break it!");
        updater.doRun();
        assertThat(pool.getError().getMessage(), startsWith("No file named 'config' found in Config Repository"));
        assertTrue(pool.isActivated());

        // TODO many more to cover...
    }

    @Test @Ignore
    public void ui() throws Exception {
        injectDummyConfigRepo();
        Timer.get().schedule(new Runnable() {
            private final Random rand = new Random();
            @Override public void run() {
                List<String> owners = Arrays.asList("https://a.com", "https://b.org", "http://10.8.0.14");
                List<String> labels = Arrays.asList("soalris11", "windows", "sparc", "w2k16");
                for (;;) {
                    String ownerUrl = owners.get(rand.nextInt(owners.size()));
                    new ReservationTask(
                            new ExecutorJenkins(ownerUrl, ownerUrl.replaceAll("\\W", "")),
                            Label.get(
                                    labels.get(rand.nextInt(labels.size()))
                            )
                    ).schedule();
                    System.out.println('.');
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, 0, TimeUnit.SECONDS);
        j.interactiveBreak();
    }

    private static class MockTask extends ReservationTask {
        final SharedComputer actuallyRunOn[] = new SharedComputer[1];
        public MockTask(@Nonnull ExecutorJenkins owner, @Nonnull Label label) {
            super(owner, label);
        }

        @Override
        public @CheckForNull Queue.Executable createExecutable() throws IOException {
            return new ReservationExecutable(this) {
                @Override
                public void run() throws AsynchronousExecution {
                    actuallyRunOn[0] = (SharedComputer) Executor.currentExecutor().getOwner();
                    perform();
                }
            };
        }

        public void perform() {
            // NOOOP until overriden
        }
    }

    private @Nonnull SharedNode getNode(String name) {
        Node node = j.jenkins.getNode(name);
        assertNotNull("No such node " + name, node);
        return (SharedNode) node;
    }

    private GitClient injectDummyConfigRepo() throws Exception {
        File orig = new File(getClass().getResource("dummy_config_repo").toURI());
        assertTrue(orig.isDirectory());
        File repo = tmp.newFolder();
        FileUtils.copyDirectory(orig, repo);

        StreamTaskListener listener = new StreamTaskListener(System.err, Charset.defaultCharset());
        GitClient git = Git.with(listener, new EnvVars()).in(repo).using("git").getClient();
        git.init();
        git.add("*");
        git.commit("Init");

        System.setProperty(CONFIG_REPO_PROPERTY_NAME, repo.getAbsolutePath());
        Pool.Updater.getInstance().doRun();

        return git;
    }

    private static class BlockingTask extends MockTask {
        final OneShotEvent running = new OneShotEvent();
        final OneShotEvent done = new OneShotEvent();

        public BlockingTask(Label label) {
            super(PoolTest.DUMMY_OWNER, label);
        }

        @Override public void perform() {
            running.signal();
            try {
                done.block();
            } catch (InterruptedException e) {
                // Proceed
            }
        }
    }
}

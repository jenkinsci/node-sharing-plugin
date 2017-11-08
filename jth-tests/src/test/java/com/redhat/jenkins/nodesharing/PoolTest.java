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

import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule.BlockingTask;
import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule.MockTask;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.SharedNode;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.ScheduleResult;
import hudson.slaves.DumbSlave;
import jenkins.util.Timer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

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

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test
    public void inactiveWithNoProperty() throws Exception {
        System.clearProperty(CONFIG_REPO_PROPERTY_NAME);

        Pool.Updater.getInstance().doRun();
        Pool pool = Pool.getInstance();
        assertNull(pool.getConfig());
        assertThat(pool.getError().getMessage(), startsWith("Node sharing Config Repo not configured"));
        MatcherAssert.assertThat(j.jenkins.getNodes(), Matchers.<Node>emptyIterable());
    }

    @Test
    public void readConfigFromRepo() throws Exception {
        j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

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
        GitClient git = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        Node win1 = j.getNode("win1.orchestrator");
        assertEquals("windows w2k12", win1.getLabelString());
        assertTrue(win1.toComputer().isOnline());

        MatcherAssert.assertThat(j.jenkins.getComputers(), arrayWithSize(4));

        // Same changes re-applied with no inventory change
        git.getWorkTree().child("fake_change").touch(0);
        git.add("*");
        git.commit("Update"); // New commit is needed to force computer update

        for (int i = 0; i < 2; i++) { // Update with no changes preserves state
            Pool.Updater.getInstance().doRun();

            MatcherAssert.assertThat(j.jenkins.getComputers(), arrayWithSize(4));
            assertSame(win1, j.getNode("win1.orchestrator"));
            assertSame(win1.toComputer(), j.getNode("win1.orchestrator").toComputer());
        }
    }

    @Test
    public void updateComputers() throws Exception {
        DumbSlave doNotTouchMe = j.createOnlineSlave(); // There is no reason for using some other slave kinds on orchestrator but ...
        GitClient git = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        Assert.assertEquals("windows w2k16", j.getNode("win2.orchestrator").getLabelString());
        Assert.assertEquals("solaris11 sparc", j.getNode("solaris1.orchestrator").getLabelString());
        assertNull(j.jenkins.getNode("windows.orchestrator"));

        Node nodeW1 = j.getNode("win1.orchestrator");
        Computer computerW1 = nodeW1.toComputer();

        // Update
        FilePath workTree = git.getWorkTree().child("nodes");
        workTree.child("win2.orchestrator.xml").renameTo(workTree.child("windows.orchestrator.xml")); // Technically, we should rename the attribute as well
        FilePath solarisXml = workTree.child("solaris1.orchestrator.xml");
        String newConfig = solarisXml.readToString().replace("solaris11", "solaris12");
        solarisXml.write(newConfig, Charset.defaultCharset().name());
        git.add("nodes/*");
        git.commit("Update");
        Pool.Updater.getInstance().doRun();

        Assert.assertEquals("windows w2k16", j.getNode("windows.orchestrator").getLabelString());
        Assert.assertEquals("solaris12 sparc", j.getNode("solaris1.orchestrator").getLabelString());
        assertNull(j.jenkins.getNode("win2.orchestrator"));
        assertNull(j.jenkins.getComputer("win2.orchestrator"));
        assertSame(nodeW1, j.getNode("win1.orchestrator"));
        assertSame(computerW1, j.getNode("win1.orchestrator").toComputer());

        assertNotNull(j.jenkins.getNode(doNotTouchMe.getNodeName()));
    }

    @Test
    public void workloadMapping() throws Exception {
        j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        MockTask task = new MockTask(j.DUMMY_OWNER, Label.get("solaris11"));
        Queue.Item item = task.schedule();
        assertEquals("jenkins42", item.task.getFullDisplayName());
        item.getFuture().get();
        Assert.assertEquals(j.getNode("solaris1.orchestrator").toComputer(), task.actuallyRunOn[0]);


        task = new MockTask(j.DUMMY_OWNER, Label.get("windows"));
        task.schedule().getFuture().get();
        MatcherAssert.assertThat(task.actuallyRunOn[0].getName(), startsWith("win"));

        // Never schedule labels we do not serve - including empty one
        task = new MockTask(j.DUMMY_OWNER, Label.get(""));
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
        final String DELETED_NODE = "solaris1.orchestrator";
        GitClient git = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        BlockingTask task = new BlockingTask(Label.get("solaris11"));
        task.schedule();
        task.running.block();
        assertFalse("Computer occupied", j.getNode(DELETED_NODE).toComputer().isIdle());

        assertTrue(git.getWorkTree().child("nodes").child(DELETED_NODE + ".xml").delete());
        git.add("*");
        git.commit("Remove running node from config repo");
        Pool.Updater.getInstance().doRun();

        assertFalse("Node still exists and occupied", j.getNode(DELETED_NODE).toComputer().isIdle());
        Thread.sleep(1000); // It is not an accident
        Pool.Updater.getInstance().doRun(); // Trigger the check
        assertFalse("Node still exists and occupied", j.getNode(DELETED_NODE).toComputer().isIdle());

        task.done.signal();
        j.waitUntilNoActivity();
        // Trigger the check
        j.jenkins.getExtensionList(SharedNode.DanglingNodeDeleter.class).iterator().next().doRun();
        assertNull("Node removed", j.jenkins.getNode(DELETED_NODE));
        assertNull("Computer removed", j.jenkins.getComputer(DELETED_NODE));
    }

    @Test
    public void brokenConfig() throws Exception {
        Pool pool = Pool.getInstance();
        Pool.Updater updater = Pool.Updater.getInstance();

        GitClient cr = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        cr.getWorkTree().child("config").write("No orchestrator url here", "cp1250" /*muahaha*/);
        cr.add("*");
        cr.commit("Break it!");
        updater.doRun();
        assertThat(pool.getError().getMessage(), startsWith("No orchestrator.url specified by Config Repository"));
        assertTrue(pool.isActivated());

        cr = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        cr.getWorkTree().child("config").delete();
        cr.add("*");
        cr.commit("Break it!");
        updater.doRun();
        assertThat(pool.getError().getMessage(), startsWith("No file named 'config' found in Config Repository"));
        assertTrue(pool.isActivated());

        // TODO many more to cover...
        // Executor URL/endpoint not reachable
        // Executor name can not be used for computer
        // Executor config defective
        // Multiple Executors with same URL / name
    }

    @Test @Ignore
    public void ui() throws Exception {
        j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
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
}

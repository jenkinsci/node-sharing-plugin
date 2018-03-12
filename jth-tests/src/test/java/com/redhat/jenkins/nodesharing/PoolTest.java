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

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule.BlockingTask;
import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule.MockTask;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.Pool.Updater;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ShareableNode;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.ScheduleResult;
import hudson.slaves.DumbSlave;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.redhat.jenkins.nodesharingbackend.Pool.CONFIG_REPO_PROPERTY_NAME;
import static com.redhat.jenkins.nodesharingbackend.Pool.Updater.getInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PoolTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test
    public void inactiveWithNoProperty() throws Exception {
        System.clearProperty(CONFIG_REPO_PROPERTY_NAME);

        getInstance().doRun();
        Pool pool = Pool.getInstance();
        try {
            pool.getConfig();
            fail();
        } catch (Pool.PoolMisconfigured ex) {
            assertEquals("Node sharing Config Repo not configured by 'com.redhat.jenkins.nodesharingbackend.Pool.ENDPOINT' property", ex.getMessage());
        }
        assertReports("Node sharing Config Repo not configured by 'com.redhat.jenkins.nodesharingbackend.Pool.ENDPOINT' property");
        MatcherAssert.assertThat(j.jenkins.getNodes(), Matchers.<Node>emptyIterable());
    }

    @Test(expected = AbortException.class)
    public void singleInstanceCanNotPlayBothRoles() throws Exception {
        ConfigRepoAdminMonitor._checkNodeSharingRole();
    }

    private Throwable getConfigTaskException(String context) {
        return Pool.ADMIN_MONITOR.getErrors().get(context);
    }

    @Test
    public void readConfigFromRepo() throws Exception {
        j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        Pool pool = Pool.getInstance();
        Map<String, String> config = pool.getConfig().getConfig();
        assertEquals("https://dummy.test", config.get("orchestrator.url"));

        assertThat(pool.getConfig().getJenkinses(), containsInAnyOrder(
                new ExecutorJenkins("https://jenkins1.acme.com", "jenkins1"),
                new ExecutorJenkins("https://jenkins1.acme.com:80/context-path", "jenkins2")
        ));

        assertFalse(Pool.ADMIN_MONITOR.isActivated());
    }

    @Test
    public void populateComputers() throws Exception {
        GitClient git = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        assertNull(getConfigTaskException("config-repo"));
        Node win1 = j.getNode("win1.acme.com");
        assertEquals("windows w2k12", win1.getLabelString());
        assertTrue(win1.toComputer().isOnline());

        MatcherAssert.assertThat(j.jenkins.getComputers(), arrayWithSize(5));

        // Same changes re-applied with no inventory change
        git.getWorkTree().child("fake_change").touch(0);
        git.add("*");
        git.commit("Update"); // New commit is needed to force computer update

        for (int i = 0; i < 2; i++) { // Update with no changes preserves state
            getInstance().doRun();

            MatcherAssert.assertThat(j.jenkins.getComputers(), arrayWithSize(5));
            assertSame(win1, j.getNode("win1.acme.com"));
            assertSame(win1.toComputer(), j.getNode("win1.acme.com").toComputer());
        }
    }

    @Test
    public void updateComputers() throws Exception {
        DumbSlave doNotTouchMe = j.createOnlineSlave(); // There is no reason for using some other slave kinds on orchestrator but ...
        GitClient git = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        Assert.assertEquals("windows w2k16", j.getNode("win2.acme.com").getLabelString());
        Assert.assertEquals("solaris11 sparc", j.getNode("solaris1.acme.com").getLabelString());
        assertNull(j.jenkins.getNode("windows.acme.com"));

        Node nodeW1 = j.getNode("win1.acme.com");
        Computer computerW1 = nodeW1.toComputer();

        // Update
        FilePath workTree = git.getWorkTree().child("nodes");
        workTree.child("win2.acme.com.xml").renameTo(workTree.child("windows.acme.com.xml")); // Technically, we should rename the attribute as well
        FilePath solarisXml = workTree.child("solaris1.acme.com.xml");
        String newConfig = solarisXml.readToString().replace("solaris11", "solaris12");
        solarisXml.write(newConfig, Charset.defaultCharset().name());
        git.add("nodes/*");
        git.commit("Update");
        getInstance().doRun();

        Assert.assertEquals("windows w2k16", j.getNode("windows.acme.com").getLabelString());
        Assert.assertEquals("solaris12 sparc", j.getNode("solaris1.acme.com").getLabelString());
        assertNull(j.jenkins.getNode("win2.acme.com"));
        assertNull(j.jenkins.getComputer("win2.acme.com"));
        assertSame(nodeW1, j.getNode("win1.acme.com"));
        assertSame(computerW1, j.getNode("win1.acme.com").toComputer());

        assertNotNull(j.jenkins.getNode(doNotTouchMe.getNodeName()));
    }

    @Test
    public void workloadMapping() throws Exception {
        j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        MockTask task = new MockTask(j.DUMMY_OWNER, Label.get("solaris11"));
        Queue.Item item = task.schedule();
        assertEquals("jenkins42", item.task.getFullDisplayName());
        item.getFuture().get();
        Assert.assertEquals(j.getNode("solaris1.acme.com").toComputer(), task.actuallyRunOn[0]);


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
        final String DELETED_NODE = "solaris1.acme.com";
        GitClient git = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        BlockingTask task = new BlockingTask(Label.get("solaris11"));
        task.schedule();
        task.running.block();
        assertFalse("Computer occupied", j.getNode(DELETED_NODE).toComputer().isIdle());

        assertTrue(git.getWorkTree().child("nodes").child(DELETED_NODE + ".xml").delete());
        git.add("*");
        git.commit("Remove running node from config repo");
        getInstance().doRun();

        assertFalse("Node still exists and occupied", j.getNode(DELETED_NODE).toComputer().isIdle());
        Thread.sleep(1000); // It is not an accident
        getInstance().doRun(); // Trigger the check
        assertFalse("Node still exists and occupied", j.getNode(DELETED_NODE).toComputer().isIdle());

        task.done.signal();
        j.waitUntilNoActivity();
        // Trigger the check
        j.jenkins.getExtensionList(ShareableNode.DanglingNodeDeleter.class).iterator().next().doRun();
        assertNull("Node removed", j.jenkins.getNode(DELETED_NODE));
        assertNull("Computer removed", j.jenkins.getComputer(DELETED_NODE));
    }

    @Test
    public void brokenConfig() throws Exception {
        Updater updater = getInstance();

        GitClient cr = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        cr.getWorkTree().child("config").write("No orchestrator url here", "cp1250" /*muahaha*/);
        cr.add("*");
        cr.commit("Break it!");
        updater.doRun();
        assertReports("ERROR: No orchestrator.url specified by Config Repository");

        Pool.ADMIN_MONITOR.clear();
        cr = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        cr.getWorkTree().child("config").delete();
        cr.add("*");
        cr.commit("Break it!");
        updater.doRun();
        assertReports("ERROR: No file named 'config' found in Config Repository");

        Pool.ADMIN_MONITOR.clear();
        cr = j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        cr.getWorkTree().child("jenkinses").deleteRecursive();
        cr.add("*");
        cr.commit("Break it!");
        updater.doRun();
        assertReports("ERROR: No directory named 'jenkinses' found in Config Repository");

        //j.interactiveBreak();

        // TODO many more to cover...
        // Executor URL/endpoint not reachable
        // Executor name can not be used for computer
        // Executor config defective
        // Multiple Executors with same URL / name
    }

    private void assertReports(String expected) throws Exception {
        UsernamePasswordCredentials creds = j.getRestCredential();

        // This test needs administer permissions
        j.getMockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to(creds.getUsername());

        String logs = j.createWebClient()
                .login(creds.getUsername(), creds.getPassword().getPlainText())
                .goTo(Pool.ADMIN_MONITOR.getUrl())
                .getWebResponse()
                .getContentAsString()
        ;
        assertThat(logs, containsString(expected));
        Throwable ex = getConfigTaskException("Primary Config Repo");
        if (ex instanceof TaskLog.TaskFailed) {
            assertThat(((TaskLog.TaskFailed) ex).getLog().readContent(), containsString(expected));
        } else {
            assertThat(ex.getMessage(), containsString(expected));
        }
        assertTrue(Pool.ADMIN_MONITOR.isActivated());
    }

    @Test @Ignore
    public void ui() throws Exception {
        j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));
        Timer.get().schedule(new Runnable() {
            private final Random rand = new Random();

            @Override public void run() {
                List<String> owners = Arrays.asList("https://a.com", "https://b.org", "http://10.8.0.14");
                List<String> labels = Arrays.asList("soalris11", "windows", "sparc", "w2k16");
                for (; ; ) {
                    String ownerUrl = owners.get(rand.nextInt(owners.size()));
                    String ownerName = ownerUrl.replaceAll("\\W", "");
                    String label = labels.get(rand.nextInt(labels.size()));
                    new ReservationTask(
                            new ExecutorJenkins(ownerUrl, ownerName),
                            Label.get(label),
                            ownerName + "-" + label
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

    @Test
    public void failRestCallsWhenNoPoolConfigRepoSpecified() throws Exception {
        final String NO_CONFIG_REPO_PROPERTY = "Node sharing Config Repo not configured by ";

        Pool pool = Pool.getInstance();
        RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), Api.getInstance().getUrlName(), j.getRestCredential());
        try {
            pool.getConfigRepoUrl();
            fail();
        } catch (Pool.PoolMisconfigured ex) {
            assertThat(ex.getMessage(), startsWith(NO_CONFIG_REPO_PROPERTY));
        }

        ResponseCaptor.Capture discover = rest.executeRequest(rest.post("discover"), new ResponseCaptor());
        assertThat(discover.statusLine.getStatusCode(), equalTo(HttpServletResponse.SC_NOT_IMPLEMENTED));
        assertThat(discover.payload, containsString(NO_CONFIG_REPO_PROPERTY));

        ResponseCaptor.Capture reportWorkload = rest.executeRequest(rest.post("reportWorkload"), new ResponseCaptor());
        assertThat(reportWorkload.statusLine.getStatusCode(), equalTo(HttpServletResponse.SC_NOT_IMPLEMENTED));
        assertThat(reportWorkload.payload, containsString(NO_CONFIG_REPO_PROPERTY));

        ResponseCaptor.Capture returnNode = rest.executeRequest(rest.post("returnNode"), new ResponseCaptor());
        assertThat(returnNode.statusLine.getStatusCode(), equalTo(HttpServletResponse.SC_NOT_IMPLEMENTED));
        assertThat(returnNode.payload, containsString(NO_CONFIG_REPO_PROPERTY));
    }

    @Test
    public void failRestCallsWhenNoSnapshotExists() throws Exception {
        GitClient cr = this.configRepo.create(getClass().getResource("dummy_config_repo"));
        cr.getWorkTree().child("config").write("No orchestrator url here", "cp1250" /*muahaha*/);
        cr.add("*");
        cr.commit("Break it!");
        j.injectConfigRepo(cr);

        Pool pool = Pool.getInstance();
        RestEndpoint rest = new RestEndpoint(j.getURL().toExternalForm(), Api.getInstance().getUrlName(), j.getRestCredential());
        try {
            System.out.println(pool.getConfig());
            fail();
        } catch (Pool.PoolMisconfigured ex) {
            assertThat(ex.getMessage(), containsString("No config snapshot loaded from "));
        }

        ResponseCaptor.Capture discover = rest.executeRequest(rest.post("discover"), new ResponseCaptor());
        assertThat(discover.statusLine.getStatusCode(), equalTo(HttpServletResponse.SC_NOT_IMPLEMENTED));
        assertThat(discover.payload, containsString("No config snapshot loaded from "));

        ResponseCaptor.Capture reportWorkload = rest.executeRequest(rest.post("reportWorkload"), new ResponseCaptor());
        assertThat(reportWorkload.statusLine.getStatusCode(), equalTo(HttpServletResponse.SC_NOT_IMPLEMENTED));
        assertThat(reportWorkload.payload, containsString("No config snapshot loaded from "));
    }
}

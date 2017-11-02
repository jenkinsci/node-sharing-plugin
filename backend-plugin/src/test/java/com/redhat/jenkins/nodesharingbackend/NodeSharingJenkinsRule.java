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
package com.redhat.jenkins.nodesharingbackend;

import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import hudson.EnvVars;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.util.OneShotEvent;
import hudson.util.StreamTaskListener;
import jenkins.model.queue.AsynchronousExecution;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import static com.redhat.jenkins.nodesharingbackend.Pool.CONFIG_REPO_PROPERTY_NAME;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class NodeSharingJenkinsRule extends JenkinsRule {

    public static final ExecutorJenkins DUMMY_OWNER = new ExecutorJenkins("https://jenkins42.acme.com", "jenkins42");

    // Hook it in
    private TemporaryFolder tmp = new TemporaryFolder();

    @Override
    public void before() throws Throwable {
        super.before();
        tmp.create();
    }

    @Override
    public void after() throws Exception {
        tmp.delete();
        super.after();
    }

    protected @Nonnull SharedComputer getComputer(String name) {
        return (SharedComputer) getNode(name).toComputer();
    }

    protected @Nonnull SharedNode getNode(String name) {
        Node node = jenkins.getNode(name);
        assertNotNull("No such node " + name, node);
        return (SharedNode) node;
    }

    protected GitClient injectDummyConfigRepo() throws Exception {
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

    protected static class BlockingTask extends MockTask {
        final OneShotEvent running = new OneShotEvent();
        final OneShotEvent done = new OneShotEvent();

        public BlockingTask(Label label) {
            super(DUMMY_OWNER, label);
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

    protected static class MockTask extends ReservationTask {
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
}

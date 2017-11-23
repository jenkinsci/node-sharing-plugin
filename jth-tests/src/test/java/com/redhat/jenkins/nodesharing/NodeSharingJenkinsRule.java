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

import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.SharedComputer;
import com.redhat.jenkins.nodesharingbackend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharingfrontend.launcher.DummyComputerLauncherFactory;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.util.OneShotEvent;
import jenkins.model.queue.AsynchronousExecution;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jvnet.hudson.test.JenkinsRule;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;

import static com.redhat.jenkins.nodesharingbackend.Pool.CONFIG_REPO_PROPERTY_NAME;
import static org.junit.Assert.assertNotNull;

public class NodeSharingJenkinsRule extends JenkinsRule {

    public static final ExecutorJenkins DUMMY_OWNER = new ExecutorJenkins("https://jenkins42.acme.com", "jenkins42");

    protected @Nonnull SharedComputer getComputer(String name) {
        return (SharedComputer) getNode(name).toComputer();
    }

    protected @Nonnull SharedNode getNode(String name) {
        Node node = jenkins.getNode(name);
        assertNotNull("No such node " + name, node);
        return (SharedNode) node;
    }

    protected GitClient injectConfigRepo(GitClient repoClient) throws Exception {
        System.setProperty(CONFIG_REPO_PROPERTY_NAME, repoClient.getWorkTree().getRemote());
System.out.println(repoClient.getWorkTree().getRemote());
        Pool.Updater.getInstance().doRun();

        return repoClient;
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

    /**
     * Create a new {@link SharedNodeCloud} instance and attach it to jenkins
     *
     * @param configRepoUrl URL for mocked Config repo.
     * @return created {@link SharedNodeCloud} instance
     */
    @Nonnull
    public SharedNodeCloud addSharedNodeCloud(@Nonnull final String configRepoUrl) {
        SharedNodeCloud cloud = new SharedNodeCloud(configRepoUrl, "", null);
        cloud.setLauncherFactory(new DummyComputerLauncherFactory());
        jenkins.clouds.add(cloud);
        cloud.setOperational();
        return cloud;
    }

}

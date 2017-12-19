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

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.SharedComputer;
import com.redhat.jenkins.nodesharingbackend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharingfrontend.WorkloadReporter;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.redhat.jenkins.nodesharingbackend.Pool.CONFIG_REPO_PROPERTY_NAME;
import static org.junit.Assert.assertNotNull;

public class NodeSharingJenkinsRule extends JenkinsRule {

    public static final ExecutorJenkins DUMMY_OWNER = new ExecutorJenkins("https://jenkins42.acme.com", "jenkins42");
    private UsernamePasswordCredentials cred;

    protected @Nonnull SharedComputer getComputer(String name) {
        return (SharedComputer) getNode(name).toComputer();
    }

    public Statement apply(final Statement base, final Description description) {
        Statement wrappedBase = new Statement() {
            @Override public void evaluate() throws Throwable {
                jenkins.setSecurityRealm(createDummySecurityRealm());

                MockAuthorizationStrategy mas = new MockAuthorizationStrategy();
                mas.grant(Jenkins.READ, RestEndpoint.INVOKE).everywhere().to("jerry");
                jenkins.setAuthorizationStrategy(mas);

                cred = new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "fake-id", "Testing node sharing credential", "jerry", "jerry"
                );

                base.evaluate();
            }
        };
        return super.apply(wrappedBase, description);
    }

    protected @Nonnull SharedNode getNode(String name) {
        Node node = jenkins.getNode(name);
        assertNotNull("No such node " + name, node);
        return (SharedNode) node;
    }

    protected GitClient injectConfigRepo(GitClient repoClient) throws Exception {
        System.setProperty(CONFIG_REPO_PROPERTY_NAME, repoClient.getWorkTree().getRemote());
        Pool.Updater.getInstance().doRun();

        return repoClient;
    }

    protected List<ReservationTask> getScheduledReservations() {
        ArrayList<ReservationTask> out = new ArrayList<>();
        for (Queue.Item item : jenkins.getQueue().getItems()) {
            if (item.task instanceof ReservationTask) {
                out.add((ReservationTask) item.task);
            }
        }
        Collections.reverse(out);
        return out;
    }

    public UsernamePasswordCredentials getRestCredential() {
        return cred;
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

    /**
     * Mock task to represent fake reservation task. To be run on orchestrator only.
     */
    protected static class MockTask extends ReservationTask {
        final SharedComputer actuallyRunOn[] = new SharedComputer[1];
        public MockTask(@Nonnull ExecutorJenkins owner, @Nonnull Label label) {
            super(owner, label, "MockTask");
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
        SharedNodeCloud cloud = new SharedNodeCloud(configRepoUrl, "", "", null);
        jenkins.clouds.add(cloud);
        return cloud;
    }

    static final class BlockingBuilder extends TestBuilder {
        final OneShotEvent start = new OneShotEvent();
        final OneShotEvent end = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            start.signal();
            end.block();
            return true;
        }
    }

    static class BlockingCommandLauncher extends CommandLauncher {
        final OneShotEvent start = new OneShotEvent();
        final OneShotEvent end = new OneShotEvent();

        public BlockingCommandLauncher(String command) {
            super(command);
        }

        @Override
        public void launch(SlaveComputer computer, final TaskListener listener) {
            start.signal();
            try {
                end.block();
            } catch (InterruptedException e) { }
            super.launch(computer, listener);
        }
    }

    static class ConnectingSlave extends Slave implements EphemeralNode {
        public ConnectingSlave(String name,
                               String nodeDescription,
                               String remoteFS,
                               String numExecutors,
                               Mode mode,
                               String labelString,
                               ComputerLauncher launcher,
                               RetentionStrategy retentionStrategy,
                               List<? extends NodeProperty<?>> nodeProperties
        ) throws IOException, Descriptor.FormException {
            super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher,
                    retentionStrategy, nodeProperties);
        }

        @Override
        public ConnectingSlave asNode() {
            return this;
        }
    }

    /**
     * Trigger workload update now from executor
     */
    protected void reportWorkloadToOrchestrator() throws Exception {
        WorkloadReporter.all().get(WorkloadReporter.class).doRun();
    }
}

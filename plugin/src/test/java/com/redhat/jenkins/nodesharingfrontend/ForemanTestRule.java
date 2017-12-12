package com.redhat.jenkins.nodesharingfrontend;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AdministrativeMonitor;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import hudson.slaves.NodeProvisioner;
import hudson.tasks.Builder;
import hudson.util.OneShotEvent;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import static org.junit.Assert.assertTrue;

/**
 * Test utils for plugin functional testing.
 *
 * @author pjanouse
 */
public final class ForemanTestRule extends JenkinsRule {
    private static AsyncResourceDisposer disposer = null;

    @Override
    public Statement apply(Statement base, Description description) {
        final Statement jenkinsRuleStatement = super.apply(base, description);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                NodeProvisioner.NodeProvisionerInvoker.INITIALDELAY = NodeProvisioner.NodeProvisionerInvoker.RECURRENCEPERIOD = 1000;
                jenkinsRuleStatement.evaluate();
            }
        };
    }

    /**
     * Start a project with an infinite build step and wait until signal to finish
     *
     * @param project {@link FreeStyleProject} to start
     * @param finish {@link OneShotEvent} to signal to finish a build
     * @return A {@link Future} object represents the started build
     * @throws Exception if somethink wrong happened
     */
    @Nonnull
    public Future<FreeStyleBuild> startBlockingAndFinishingBuild(@Nonnull final FreeStyleProject project,
                                                                 @Nonnull final OneShotEvent finish) throws Exception {
        final OneShotEvent block = new OneShotEvent();

        project.getBuildersList().add(new BlockingAndFinishingBuilder(block, finish));

        Future<FreeStyleBuild> r = project.scheduleBuild2(0);
        while (!project.isBuilding()) {
            // Force to process the queue
            jenkins.getQueue().scheduleMaintenance();
            Thread.sleep(TestUtils.SLEEP_DURATION);
        }
        block.block();  // wait until we are safe to interrupt
        assertTrue(project.getLastBuild().isBuilding());

        return r;
    }

    /**
     * Returt the instance of {@link AsyncResourceDisposer}.
     *
     * @return Found {@link AsyncResourceDisposer} instance.
     */
    @Nonnull
    public AsyncResourceDisposer getAsyncResourceDisposer() {
        if (disposer == null) {
            AdministrativeMonitor adminMonitor = jenkins.getAdministrativeMonitor("AsyncResourceDisposer");
            assertTrue("adminMonitor not null for AsyncResourceDisposer", adminMonitor != null);
            assertTrue("adminMonitor is instanceof AsyncResourceDisposer", adminMonitor instanceof AsyncResourceDisposer);
            disposer = (AsyncResourceDisposer) adminMonitor;
        }
        return disposer;
    }

    /**
     * Block execution until AsyncResourceDisposer is clean, but max 30 periods.
     *
     * @throws Exception if occurs.
     */
    public void waitForEmptyAsyncResourceDisposer() throws Exception {
        waitForEmptyAsyncResourceDisposer(new CountDownLatch(30));
    }

    /**
     * Block execution until AsyncResourceDisposer is clean.
     *
     * @param cleanedCheckLatch a {@link CountDownLatch} instance which can act as a timeout
     * @throws Exception if occurs.
     */
    public void waitForEmptyAsyncResourceDisposer(@Nonnull final CountDownLatch cleanedCheckLatch ) throws Exception {
        while(cleanedCheckLatch.getCount() >= 0) {
            boolean foundOurDisposalItem = false;
            for (AsyncResourceDisposer.WorkItem item: getAsyncResourceDisposer().getBacklog()) {
                Disposable disposableItem = item.getDisposable();
                if (disposableItem instanceof DisposableImpl) {
                    foundOurDisposalItem = true;
                }
            }
            if (!foundOurDisposalItem) {
                break;
            }
            getAsyncResourceDisposer().reschedule();
            Thread.sleep(TestUtils.SLEEP_DURATION);
            cleanedCheckLatch.countDown();
        }
        if (cleanedCheckLatch.getCount() <= 0) {
            throw new Exception("backlog of DisposableImpl items did not get cleaned up: " + cleanedCheckLatch.getCount());
        }
    }

    /**
     * Create a new {@link SharedNodeCloud} instance and attach it to jenkins
     *
     * @param cloudName name of the cloud.
     * @param url URL for mocking.
     * @return created {@link SharedNodeCloud} instance
     */
    @Nonnull
    public SharedNodeCloud addForemanCloud(@Nonnull final String cloudName, @Nonnull final String url) {
        SharedNodeCloud foremanCloud = new SharedNodeCloud(url, "", 1);
        jenkins.clouds.add(foremanCloud);
        foremanCloud.setOperational();
        return foremanCloud;
    }

    private static final class BlockingAndFinishingBuilder extends Builder {
        private final OneShotEvent block;
        private final OneShotEvent finish;

        private BlockingAndFinishingBuilder(@Nonnull final OneShotEvent block, @Nonnull final OneShotEvent finish) {
            this.block = block;
            this.finish = finish;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            VirtualChannel channel = launcher.getChannel();
            Node node = build.getBuiltOn();

            block.signal(); // we are safe to be interrupted
            for (;;) {
                // Go out if we should finish
                if (finish.isSignaled())
                    break;

                // Keep using the channel
                channel.call(node.getClockDifferenceCallable());
                Thread.sleep(TestUtils.SLEEP_DURATION / 10);
            }
            return true;
        }

        @TestExtension("disconnectCause")
        public static class DescriptorImpl extends Descriptor<Builder> {
            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }
}

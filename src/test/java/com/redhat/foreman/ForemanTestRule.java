package com.redhat.foreman;

import com.redhat.foreman.launcher.ForemanDummyComputerLauncherFactory;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AdministrativeMonitor;
import hudson.model.AsyncPeriodicWork;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.NodeProvisioner;
import hudson.tasks.Builder;
import hudson.util.OneShotEvent;
import hudson.util.Secret;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URL;
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

    @Nonnull
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
     * Force idle slave cleanup now.
     */
    public void triggerCleanupThread() {
        jenkins.getExtensionList(AsyncPeriodicWork.class).get(ForemanCleanupThread.class).execute(TaskListener.NULL);
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

    public void waitForEmptyAsyncResourceDisposer() throws Exception {
        waitForEmptyAsyncResourceDisposer(new CountDownLatch(30));
    }

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

    @Nonnull
    public ForemanSharedNodeCloud addForemanCloud(@Nonnull final String cloudName, @Nonnull final String url,
                                                  @Nonnull final String user, @Nonnull final String password) {
        ForemanSharedNodeCloud foremanCloud = new ForemanSharedNodeCloud(cloudName, url, user, Secret.fromString(password),
                "", 1);
        foremanCloud.setLauncherFactory(new ForemanDummyComputerLauncherFactory());
        jenkins.clouds.add(foremanCloud);
        foremanCloud.updateHostData();
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

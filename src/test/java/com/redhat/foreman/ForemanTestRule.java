package com.redhat.foreman;

import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.NodeProvisioner;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Test utils for plugin functional testing.
 *
 * @author pjanouse
 */
public final class ForemanTestRule extends JenkinsRule {

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

}

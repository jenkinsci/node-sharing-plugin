package com.redhat.foreman;

import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Test utils for plugin functional testing.
 *
 * @author pjanouse
 */
public final class ForemanTestRule extends JenkinsRule {
    /**
     * Force idle slave cleanup now.
     */
    public void triggerCleanupThread() {
        jenkins.getExtensionList(AsyncPeriodicWork.class).get(ForemanCleanupThread.class).execute(TaskListener.NULL);
    }

}

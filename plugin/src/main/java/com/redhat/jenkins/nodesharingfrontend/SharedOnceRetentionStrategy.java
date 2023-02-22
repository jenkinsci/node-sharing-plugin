package com.redhat.jenkins.nodesharingfrontend;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.OneOffExecutor;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.OfflineCause;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SharedOnceRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(SharedOnceRetentionStrategy.class.getName());

    private boolean terminating;

    private volatile boolean tempOffline;

    private int idleMinutes;

    /**
     * Creates the retention strategy.
     * @param idleMinutes number of minutes of idleness after which to kill the slave; serves a backup in case the strategy fails to detect the end of a task
     */
    public SharedOnceRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }

    @Override
    public long check(final AbstractCloudComputer c) {
        // When the slave is idle we should disable accepting tasks and check to see if it is already trying to
        // terminate. If it's not already trying to terminate then lets terminate manually.
        if (c.isIdle() && !disabled) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit.MINUTES.toMillis(idleMinutes)) {
                LOGGER.log(Level.INFO, "Disconnecting {0}", c.getName());
                done(c);
            }
        }
        // Return one because we want to check every minute if idle.
        return 1;
    }

    @Override
    public void start(AbstractCloudComputer c) {
        super.start(c);
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {}

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    private void done(Executor executor) {
        final AbstractCloudComputer<?> c = (AbstractCloudComputer) executor.getOwner();
        Queue.Executable exec = executor.getCurrentExecutable();
        if (executor instanceof OneOffExecutor) {
            LOGGER.log(Level.INFO, "not terminating {0} because {1} was a flyweight task", new Object[] {c.getName(), exec});
            return;
        }
        LOGGER.log(Level.INFO, "terminating {0} since {1} seems to be finished", new Object[] {c.getName(), exec});
        done(c);
    }

    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="not a real Callable")
    @VisibleForTesting
    public void done(final AbstractCloudComputer<?> c) {
        c.setAcceptingTasks(false); // just in case
        if (c.isOffline() && c.getOfflineCause() instanceof OfflineCause.UserCause) {
            if (!tempOffline) {
                LOGGER.log(Level.INFO, "termination of " + c.getName() + " is postponed due to temporary offline state");
                tempOffline = true;
            }
            return;
        }
        synchronized (this) {
            if (terminating) {
                return;
            }
            terminating = true;
        }
        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    AbstractCloudSlave node = c.getNode();
                    if (node != null) {
                        try (ACLContext ctx = ACL.as(ACL.SYSTEM)) {
                            node.terminate();
                        }
                    }
                    LOGGER.log(Level.INFO, "Terminating computer " + c.getName());
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                } finally {
                    synchronized (SharedOnceRetentionStrategy.this) {
                        terminating = false;
                    }
                }
            }
        });
    }
}

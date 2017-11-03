package com.redhat.jenkins.nodesharingfrontend;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.OneOffExecutor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.util.TimeUnit2;
import jenkins.security.NotReallyRoleSensitiveCallable;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ForemanOnceRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(ForemanOnceRetentionStrategy.class.getName());

    private transient boolean terminating;

    private int idleMinutes;

    /**
     * Creates the retention strategy.
     * @param idleMinutes number of minutes of idleness after which to kill the slave; serves a backup in case the strategy fails to detect the end of a task
     */
    public ForemanOnceRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }

    @Override
    public long check(final AbstractCloudComputer c) {
        // When the slave is idle we should disable accepting tasks and check to see if it is already trying to
        // terminate. If it's not already trying to terminate then lets terminate manually.
        if (c.isIdle() && !disabled) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleMinutes)) {
                LOGGER.log(Level.INFO, "Disconnecting {0}", c.getName());
                done(c);
            }
        }

        // Return one because we want to check every minute if idle.
        return 1;
    }

    @Override public void start(AbstractCloudComputer c) {
        super.start(c);
    }

    @Override public void taskAccepted(Executor executor, Queue.Task task) {}

    @Override public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    private void done(Executor executor) {
        final AbstractCloudComputer<?> c = (AbstractCloudComputer) executor.getOwner();
        Queue.Executable exec = executor.getCurrentExecutable();
        if (executor instanceof OneOffExecutor) {
            LOGGER.log(Level.INFO, "not terminating {0} because {1} was a flyweight task", new Object[] {c.getName(), exec});
            return;
        }
//        if (exec instanceof ContinuableExecutable && ((ContinuableExecutable) exec).willContinue()) {
//            LOGGER.log(Level.FINE, "not terminating {0} because {1} says it will be continued", new Object[] {c.getName(), exec});
//            return;
//        }
        LOGGER.log(Level.INFO, "terminating {0} since {1} seems to be finished", new Object[] {c.getName(), exec});
        done(c);
    }

    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="not a real Callable")
    private void done(final AbstractCloudComputer<?> c) {
        c.setAcceptingTasks(false); // just in case
        synchronized (this) {
            if (terminating) {
                return;
            }
            terminating = true;
        }
        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                Queue.withLock(new NotReallyRoleSensitiveCallable<Void,RuntimeException>() {
                    @Override public Void call() {
                        try {
                            AbstractCloudSlave node = c.getNode();
                            if (node != null) {
                                node.terminate();
                            }
                        } catch (InterruptedException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                            synchronized (ForemanOnceRetentionStrategy.this) {
                                terminating = false;
                            }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                            synchronized (ForemanOnceRetentionStrategy.this) {
                                terminating = false;
                            }
                        }
                        return null;
                    }
                });
            }
        });
    }
}

package com.redhat.foreman;

import java.io.IOException;

import java.util.logging.Logger;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.User;
import hudson.model.Queue.Task;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.http.HttpServletResponse;

/**
 * Foreman Cloud Computer.
 */
public class ForemanComputer extends AbstractCloudComputer<ForemanSharedNode> {

    private static final Logger LOGGER = Logger.getLogger(ForemanComputer.class.getName());
    private final Object pendingDeleteLock = new Object();

    @Override
    public void taskCompleted(Executor executor, Task task, long durationMS) {
        eagerlyReturnNode(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Task task, long durationMS, Throwable problems) {
        eagerlyReturnNode(executor);
    }

    @Override
    public ForemanSharedNode getNode() {
        return super.getNode();
    }

    @Override
    protected void kill() {
        setPendingDelete(true);
        super.kill();
        try {
            ForemanSharedNode node = this.getNode();
            if (node != null) {
                node.terminate();
            }
        } catch (InterruptedException e) {
            LOGGER.warning("Error during ForemanComputer kill() - " + e.getMessage());
        } catch (IOException e) {
            LOGGER.warning("Error during ForemanComputer kill() - " + e.getMessage());
        }
    }

    /**
     * Utility method to terminate a Foreman shared node.
     *
     * @param c Computer
     * @throws IOException if occurs.
     * @throws InterruptedException if occurs.
     */
    static void terminateForemanComputer(Computer c) throws IOException, InterruptedException {
        if (c instanceof ForemanComputer) {
            ForemanComputer fc = (ForemanComputer)c;
            Node node = fc.getNode();
            if (node != null) {
                ForemanSharedNode sharedNode = (ForemanSharedNode)node;
                sharedNode.terminate();
                LOGGER.info("Deleted slave " + node.getDisplayName());
            }
        }
    }

    /**
     * We want to eagerly return the node to Foreman.
     *
     * @param owner Computer.
     */
    private synchronized void eagerlyReturnNodeLater(final Computer owner) {
        Node node = owner.getNode();
        if (node instanceof ForemanSharedNode) {
            ForemanSharedNode sharedNode = (ForemanSharedNode)node;
            Computer computer = sharedNode.toComputer();
            if (computer != null) {
                setPendingDelete(true);
            }
            Computer.threadPoolForRemoting.submit(new Runnable() {
                public void run() {
                    try {
                        ForemanComputer.terminateForemanComputer(owner);
                    } catch (InterruptedException e) {
                        LOGGER.warning(e.getMessage());
                    } catch (IOException e) {
                        LOGGER.warning(e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Eagerly return resource to Foreman.
     * @param executor Executor.
     */
    private void eagerlyReturnNode(Executor executor) {
        eagerlyReturnNodeLater(executor.getOwner());
    }

    /**
     * Default constructor.
     *
     * @param slave Foreman slave.
     */
    public ForemanComputer(ForemanSharedNode slave) {
        super(slave);
    }

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Is slave pending termination.
     */
    public boolean isPendingDelete() {
        // No need  to synchronize reading as offlineCause is volatile
        return offlineCause instanceof PendingTermination;
    }

    /**
     * Flag the slave to be removed
     *
     * @return Old value.
     */
    public boolean setPendingDelete(final boolean newStatus) {
        synchronized (pendingDeleteLock) {
            boolean oldStatus = isPendingDelete();
            if (oldStatus == newStatus) {
                return oldStatus;
            }

            LOGGER.info("Setting " + getName() + " pending delete status to " + newStatus);
            if (newStatus) {
                setTemporarilyOffline(true, PENDING_TERMINATION);
            } else {
                setTemporarilyOffline(true, null);
            }
            return oldStatus;
        }
    }

    // Singleton
    private static final PendingTermination PENDING_TERMINATION = new PendingTermination();

    private static final class PendingTermination extends OfflineCause.SimpleOfflineCause {

        protected PendingTermination() {
            super(Messages._DeletedCause());
        }
    }
}

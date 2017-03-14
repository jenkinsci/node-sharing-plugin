package com.redhat.foreman;

import java.io.IOException;

import java.util.logging.Logger;
import hudson.remoting.VirtualChannel;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue.Task;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.http.HttpServletResponse;

/**
 * Foreman Cloud Computer.
 */
public class ForemanComputer extends AbstractCloudComputer<ForemanSharedNode> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(ForemanComputer.class.getName());

    private final Object pendingDeleteLock = new Object();
    private final ProvisioningActivity.Id id;

    @Override
    public void taskCompleted(Executor executor, Task task, long durationMS) {
        eagerlyReturnNodeLater();
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Task task, long durationMS, Throwable problems) {
        eagerlyReturnNodeLater();
    }

    @Override
    public ForemanSharedNode getNode() {
        return super.getNode();
    }

    /**
     * Utility method to terminate a Foreman shared node.
     *
     * @param c The {@link Computer}
     * @throws IOException if occurs.
     * @throws InterruptedException if occurs.
     */
    private static void terminateForemanComputer(Computer c) throws IOException, InterruptedException {
        if (c instanceof ForemanComputer) {
            ForemanComputer fc = (ForemanComputer) c;
            fc.setPendingDelete(true);
            Node node = fc.getNode();
            if (node != null) {
                ForemanSharedNode sharedNode = (ForemanSharedNode) node;

                VirtualChannel channel = sharedNode.getChannel();
                if (channel != null) {
                    channel.close();
                }

                sharedNode.terminate();
                LOGGER.info("Deleted slave " + node.getDisplayName());
            }
        }
    }

    /**
     * We want to eagerly return the node to Foreman in an asynchronous thread.
     *
     * @param computer The {@link Computer}.
     */
    public static void eagerlyReturnNodeLater(final Computer computer) {
        if (computer != null && computer.getNode() instanceof ForemanSharedNode) {
            ((ForemanComputer) computer).setPendingDelete(true);
        }
    }

    /**
     * Delete the slave, terminate the instance
     *
     * @throws IOException if occurs
     * @throws InterruptedException if occurs
     */
    public void deleteSlave() throws IOException, InterruptedException {
        terminateForemanComputer(this);
    }

    /**
     * We want to eagerly return current instance of {@link ForemanComputer} in an asynchronous thread.
     */
    public void eagerlyReturnNodeLater() {
        eagerlyReturnNodeLater(this);
    }

    /**
     * Default constructor.
     *
     * @param slave Foreman slave {@link ForemanSharedNode}.
     */
    public ForemanComputer(ForemanSharedNode slave) {
        super(slave);
        id = slave.getId();
    }

    @Override
    public ProvisioningActivity.Id getId() {
        return id;
    }

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    @Restricted(NoExternalUse.class)
    public HttpResponse doDoDelete() throws IOException {
        if (!isPendingDelete()) {
            ForemanSharedNode node = getNode();
            if (node == null) {
                super.doDoDelete();
            }
            eagerlyReturnNodeLater();
        }
        return new HttpRedirect("..");
    }

    /**
     * Is slave pending termination.
     *
     * @return The current state
     */
    public boolean isPendingDelete() {
        // No need  to synchronize reading as offlineCause is volatile
        return offlineCause instanceof PendingTermination;
    }

    /**
     * Flag the slave to be removed
     *
     * @param newStatus The new status
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
                setTemporarilyOffline(false, null);
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

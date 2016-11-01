package com.redhat.foreman;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.slaves.OfflineCause;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cleanup thread for ForemanSharedNode instances which are in PendingDelete state
 *
 * @author pjanouse
 */
@Extension
@Restricted(NoExternalUse.class)
public final class ForemanCleanupThread extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(ForemanCleanupThread.class.getName());

    public ForemanCleanupThread() {
        super("ForemanCleanupThread");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN;
    }

    @Override
    public void execute(TaskListener listener) {
        final ImmutableList.Builder<ListenableFuture<?>> nodeTerminationBuilder = ImmutableList.builder();
        ListeningExecutorService executor = MoreExecutors.listeningDecorator(Computer.threadPoolForRemoting);

        for (final Computer c : Jenkins.getInstance().getComputers()) {
            if (c instanceof ForemanComputer) {
                final ForemanComputer computer = (ForemanComputer) c;

                if (!c.isIdle()) continue;

                final OfflineCause cause = computer.getOfflineCause();
                if (computer.isPendingDelete() || cause instanceof DiskSpaceMonitorDescriptor.DiskSpace) {
                    nodeTerminationBuilder.add(executor.submit(new Runnable() {
                        public void run() {
                            LOGGER.log(Level.INFO, "Deleting pending node " + computer.getName()
                                    + ". Reason: " + cause.toString());
                            try {
                                computer.deleteSlave();
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Failed to disconnect and delete "
                                        + computer.getName(), e);
                            } catch (InterruptedException e) {
                                LOGGER.log(Level.WARNING, "Failed to disconnect and delete "
                                        + computer.getName(), e);
                            } catch (Throwable e) {
                                // The fancy futures stuff ignores failures silently
                                LOGGER.log(Level.WARNING, "Failed to disconnect and delete "
                                        + computer.getName(), e);

                                // we are not supposed to try and recover from Errors
                                if (e instanceof Error) {
                                    throw (Error) e;
                                }
                            }
                        }}));
                    }
            }
        }
        Futures.getUnchecked(Futures.successfulAsList(nodeTerminationBuilder.build()));
    }

}

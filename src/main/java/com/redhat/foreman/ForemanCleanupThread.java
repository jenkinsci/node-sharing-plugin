package com.redhat.foreman;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.slaves.Cloud;
import hudson.slaves.OfflineCause;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
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
    private static final int SLEEPING_DELAY = 10000;    // in ms

    public ForemanCleanupThread() {
        super("ForemanCleanupThread");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN / 4;
    }

    @Override
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    synchronized public void execute(TaskListener listener) {
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

//    @Initializer(after = InitMilestone.COMPLETED)
//    public static void onCompleted() {
//        LOGGER.finer("[START] ForemanCleanupThread.onCompleted()");
//
//        // Make the time consuming operation in the separate thread to not block other listeners
//        new Thread("ForemanStartCleanupThread") {
//            @Override
//            public void run() {
//                runCleanup();
//            }
//        }.start();
//        LOGGER.finer("[COMPLETED] ForemanCleanupThread.onCompleted()");
//    }

    // Until resolved JENKINS-37759, then remove this class and use above onComplete()
    @Extension
    public final static class OnLoadedListener extends ItemListener {
        private transient OneShotEvent executed = null;
        private transient Object executedLock = null;

        private synchronized Object getExecutedLock() {
            if (executedLock == null) {
                executedLock = new Object();
            }
            return executedLock;
        }

        @Override
        public void onLoaded() {
            LOGGER.finer("[START] ForemanCleanupThread.OnLoadedListener.onLoaded()");
            synchronized (getExecutedLock()) {
                if (executed == null) {
                    executed = new OneShotEvent();
                }
                if (!executed.isSignaled()) {
                    executed.signal();
                } else {
                    LOGGER.finer("[COMPLETED] ForemanCleanupThread.OnLoadedListener.onLoaded() - without a new thread");
                    return;
                }
            }

            // Make the time consuming operation in the separate thread to not block other listeners
            new Thread("ForemanStartCleanupThread") {
                @Override
                public void run() {
                    runCleanup();
                }
            }.start();
            LOGGER.finer("[COMPLETED] ForemanCleanupThread.OnLoadedListener.onLoaded() - with a new thread");
        }
    }

    @SuppressFBWarnings(value = { "BC_VACUOUS_INSTANCEOF", "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE" })
    private static final void runCleanup () {
        LOGGER.info("[START] ForemanStartCleanupThread.runCleanup()");
        long time = System.currentTimeMillis();

        AsyncResourceDisposer disposer = AsyncResourceDisposer.get();

        // Find all ForemanSharedNode instances and terminate their computers.
        // As ForemanSharedNode(s) implement EphemeralNode, we shouldn't find ANY!
        for (Computer computer : Jenkins.getInstance().getComputers()) {
            try {
                if (computer instanceof ForemanComputer) {
                    LOGGER.severe("Found computer " + computer.getDisplayName() + "' which belongs under a ForemanCloud.\n"
                            + "ForemanSharedNode implements EphemeralNode, so this is a serious Jenkins bug, please report it.");
                    ForemanComputer.eagerlyReturnNodeLater(computer);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE,
                        "Unhandled exception in ForemanStartCleanupThread.runCleanup(): ",
                        e);
            }
        }

        // Wait until ForemanCleanupThread terminates every node in PendingDelete state
        boolean doCheck = true;
        while (doCheck) {
            boolean foundPendingDelete = doCheck = false;
            for (Computer computer : Jenkins.getInstance().getComputers()) {
                try {
                    if (computer instanceof ForemanComputer) {
                        ForemanComputer foremanComputer = (ForemanComputer) computer;
                        if (foremanComputer.isPendingDelete()) {
                            LOGGER.finer("Set flag to reschedule ForemanStartCleanpThread");
                            foundPendingDelete = true;
                        } else {
                            LOGGER.severe("Found computer " + computer.getDisplayName() + "' which belongs under a ForemanCloud.\n"
                                    + "We should never reach this part of the code! It's a serious Jenkins bug, please report it.");
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable e) {
                    LOGGER.log(Level.SEVERE,
                            "Unhandled exception in ForemanStartCleanupThread.runCleanup(): ",
                            e);
                }
            }

            if (foundPendingDelete) {
                LOGGER.finer("Flag to reschedule ForemanCleanThread.execute() was set, going to process it and sleep "
                        + SLEEPING_DELAY / 1000 + "s.");
                Jenkins.getInstance().getExtensionList(AsyncPeriodicWork.class).get(ForemanCleanupThread.class).doRun();
                doCheck = true;
                try {
                    Thread.sleep(SLEEPING_DELAY);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Thread sleeping interrupted!", e);
                }
            }
        }

        // Do check until every Foreman cloud is clean
        Set<ForemanSharedNodeCloud> failedCloudSet = new HashSet<ForemanSharedNodeCloud>();
        doCheck = true;
        while (doCheck) {
            boolean rescheduleDisposer = doCheck = false;
            for (Cloud cloud : Jenkins.getInstance().clouds) {
                if (cloud instanceof ForemanSharedNodeCloud) {
                    ForemanSharedNodeCloud foremanCloud = (ForemanSharedNodeCloud) cloud;
                    try {
                        if (failedCloudSet.contains(foremanCloud)) {
                            // Cleanup attempt already performed with an unhandled exception occurred
                            LOGGER.info("Cleanup of Foreman cloud '" + foremanCloud.getDisplayName()
                                    + "' failed previously, sleeping " + SLEEPING_DELAY/1000 + "s.");
                            failedCloudSet.remove(foremanCloud);
                            try {
                                Thread.sleep(SLEEPING_DELAY);
                            } catch (InterruptedException e) {
                                LOGGER.warning("Thread sleeping interrupted!");
                            }
                        }

                        if (foremanCloud.isOperational()) {
                            // Skipping - already fully cleaned-up
                            continue;
                        }

                        LOGGER.info("Found Foreman cloud '" + foremanCloud.getDisplayName() + "' for clean-up");
                        boolean foundOurDisposalItem = false;
                        // Try to find DisposableItems for this cloud
                        for (AsyncResourceDisposer.WorkItem item : disposer.getBacklog()) {
                            Disposable disposableItem = item.getDisposable();
                            if (disposableItem instanceof DisposableImpl) {
                                DisposableImpl foremanDisposableItem = (DisposableImpl) disposableItem;
                                if (foremanDisposableItem.getCloudName().compareTo(foremanCloud.getCloudName()) == 0) {
                                    LOGGER.info("Found disposable item '"
                                            + disposableItem.getDisplayName() + "' for Foreman cloud '"
                                            + foremanCloud.getCloudName() + "'");
                                    foundOurDisposalItem = true;
                                    break;
                                }
                            }
                        }

                        if (foundOurDisposalItem) {
                            // Force rescheduling
                            LOGGER.info("Set flag to reschedule AsyncResourceDisposer due to Foreman cloud '"
                                    + foremanCloud.getDisplayName() + "'");
                            rescheduleDisposer = true;
                        } else {
                            // All DisposableItems were disposed, there shouldn't be any reserved node for this cloud
                            boolean foundLeakedNode = false;
                            for (String reservedNode : foremanCloud.getForemanAPI().getAllReservedHosts()) {
                                LOGGER.warning("Found a leaked computer '" + reservedNode
                                        + "' for Foreman cloud '" + foremanCloud.getCloudName()
                                        + "'. Disposing!");
                                ForemanSharedNodeCloud.addDisposableEvent(foremanCloud.name, reservedNode);
                                rescheduleDisposer = foundLeakedNode = true;
                            }
                            if (!foundLeakedNode) {
                                // Jenkins  nad Foreman state was narrowed, we can start using this cloud
                                LOGGER.info("Unblocking ForemanSharedNodeWorker.Updater for Foreman cloud '"
                                        + foremanCloud.getDisplayName() + "'");
                                foremanCloud.setOperational();
                            }
                        }
                    } catch (Error e) {
                        throw e;
                    } catch (Throwable e) {
                        LOGGER.log(Level.SEVERE,
                                "Unhandled exception in ForemanStartCleanupThread.runCleanup() for Foreman cloud '"
                                        + foremanCloud.getDisplayName() + "' (disabling temporary this Foreman cloud): ",
                                e);
                        doCheck = true;
                        failedCloudSet.add(foremanCloud);
                    }
                }
            }

            if (rescheduleDisposer) {
                // AsyncResourceDisposer needs to be scheduled
                LOGGER.info("Flag to reschedule AsyncResourceDisposer was set, going to process it and sleep "
                        + SLEEPING_DELAY/1000 + "s.");
                doCheck = true;
                disposer.reschedule();
                try {
                    Thread.sleep(SLEEPING_DELAY);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Thread sleeping interrupted!", e);
                }
            } else {
                LOGGER.info("All ForemanSharedNode items in AsyncResourceDisposer were disposed");
            }
        }   // while

        LOGGER.info("[COMPLETED] ForemanStartCleanupThread.runCleanup() finished in "
                + Util.getTimeSpanString(System.currentTimeMillis() - time));
    }
}

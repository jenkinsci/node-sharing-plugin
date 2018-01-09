/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharingbackend;

import com.google.common.annotations.VisibleForTesting;
import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import jenkins.model.Jenkins;
import org.antlr.v4.runtime.misc.MultiMap;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Re-sync orchestrator tracked reservations with executor usage.
 *
 * There are several state to balance for individual hosts. From the executor perspective the host can either be reserved
 * for it or not. From the orchestrator perspective, the host can be idle, reserved for a given executor or missing
 * completely - presumably because of recent removal from config repo. There are several situations resulting from that:
 *
 * <h2>Agreement</h2>
 *
 * <ul>
 *     <li>All nodes agree the host is idle,</li>
 *     <li>Orchestrator agree with the ony executor that claims the host.</li>
 * </ul>
 * No action needed here as grid is in sync.
 *
 * <h2>No collision</h2>
 *
 * <ul>
 *     <li>Orchestrator tracks no reservation for the host yet one executor claims it. UC: Orchestrator failover.</li>
 *     <li>Orchestrator tracks a reservation for executor that does not report the host being reserved. UC: Executor failover or Missed returnNode call</li>
 *     <li>Executor reports reservation for host not tracked by Orchestrator. UC:Host removal and Orchestrator failover</li>
 * </ul>
 *
 * <h2>Collision</h2>
 *
 * <ul>
 *     <li>Orchestrator tracks reservation but extra executors report usage of the host. Bug or race condition.</li>
 *     <li>Several executors report reservation but orchestrator tracks none. Bug or race condition.</li>
 * </ul>
 */
@Extension
public class ReservationVerifier extends PeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(ReservationVerifier.class.getName());

    public static @Nonnull ReservationVerifier getInstance() {
        ExtensionList<ReservationVerifier> list = Jenkins.getActiveInstance().getExtensionList(ReservationVerifier.class);
        assert list.size() == 1;
        return list.iterator().next();
    }

    @Override
    public long getRecurrencePeriod() {
        return 5 * MIN;
    }

    @Override
    public void doRun() throws Exception {
        ConfigRepo.Snapshot config;
        try {
            config = Pool.getInstance().getConfig();
        } catch (Pool.PoolMisconfigured ex) {
            return; // NOOP if not configured
        }

        verify(config, Api.getInstance());
    }

    @VisibleForTesting
    public static void verify(ConfigRepo.Snapshot config, Api api) throws ExecutionException, InterruptedException {
        // When executor is removed from config repo, it might have ReservationTasks running for a while so it is
        // necessary to query these executors so the task completion can be detected.
        Set<ExecutorJenkins> jenkinses = new HashSet<>(config.getJenkinses());
        MultiMap<ExecutorJenkins, ReservationTask.ReservationExecutable> trackedReservations = trackedReservations(jenkinses);
        MultiMap<ExecutorJenkins, String> executorReservations = queryExecutorReservations(jenkinses, api);
        assert executorReservations.keySet().equals(trackedReservations.keySet());

        // TODO verify multiple executors are not using same host

        ArrayList<Future<Queue.Executable>> startingTasks = new ArrayList<>();
        for (Map.Entry<ExecutorJenkins, List<String>> er: executorReservations.entrySet()) {
            ExecutorJenkins executor = er.getKey();

            List<ReservationTask.ReservationExecutable> trackedExecutorReservations = trackedReservations.get(executor);
            hosts: for (String host: er.getValue()) {
                for (ReservationTask.ReservationExecutable e: trackedExecutorReservations) {
                    if (host.equals(e.getNodeName())) {
                        // Orchestrator and executor are in sync - host is reserved for the executor
                        continue hosts;
                    }
                }
                // TODO node can be already occupied
                // TODO schedule all and then wait
                // Schedule new reservation
                startingTasks.add(new ReservationTask(executor, Label.get(host), host).schedule().getFuture().getStartCondition());
            }

//            for (Map.Entry<ExecutorJenkins, List<ReservationTask.ReservationExecutable>> tr : trackedReservations.entrySet()) {
//                ExecutorJenkins trackedOwner = tr.getKey();
//                for (ReservationTask.ReservationExecutable trackedExecutable : tr.getValue()) {
//
//                }
//            }
//
//            if (response.getUsedNodes().isEmpty()) {
//                List<ReservationTask.ReservationExecutable> danglingExecutables = trackedReservations.get(config.getJenkinsByName(response.getExecutorName()));
//                for (ReservationTask.ReservationExecutable executable : danglingExecutables) {
//                    // Executor stopped utilizing the node without orchestrator noticing
//                    executable.complete();
//                }
//                continue;
//            }
        }

        // Wait for all newly discovered reservation tasks to be started
        for (Future<Queue.Executable> task : startingTasks) {
            task.get();
        }

//        for (String nodeName : response.getUsedNodes()) {
//            ShareableNode node = ShareableNode.getNodeByName(nodeName);
//            if (node != null) {
//                ShareableComputer shareableComputer = node.getComputer();
//                ReservationTask.ReservationExecutable reservation = shareableComputer.getReservation();
//                if (reservation == null) {
//                    // TODO schedule reservation
//                    continue;
//                }
//                ExecutorJenkins reservationOwner = reservation.getParent().getOwner();
//                if (reservationOwner.equals(owner)) continue; // We are in sync
//
//                LOGGER.severe(owner + " utilizes node '" + nodeName + "' we shared for " + reservationOwner);
//            } else {
//                LOGGER.info(owner + " utilizes node '" + nodeName + "' that is not (likely no longer) shared by orchestrator");
//            }
//        }
    }

    private static @Nonnull MultiMap<ExecutorJenkins, String> queryExecutorReservations(
            @Nonnull Set<ExecutorJenkins> jenkinses, @Nonnull Api api
    ) {
        MultiMap<ExecutorJenkins, String> responses = new MultiMap<>();
        for (ExecutorJenkins executorJenkins : jenkinses) {
            responses.put(executorJenkins, api.reportUsage(executorJenkins).getUsedNodes());
        }
        return responses;
    }

    /**
     * Get mapping between served Executor Jenkinses and their current reservations.
     *
     * Executors that does not utilize any node will be reported with empty mapping.
     */
    private static @Nonnull MultiMap<ExecutorJenkins, ReservationTask.ReservationExecutable> trackedReservations(Set<ExecutorJenkins> jenkinses) {
        MultiMap<ExecutorJenkins, ReservationTask.ReservationExecutable> all = new MultiMap<>();
        for (ExecutorJenkins jenkins : jenkinses) {
            // Make sure tracked executors without running reservation will be reported with empty mapping
            all.put(jenkins, new ArrayList<ReservationTask.ReservationExecutable>());
        }

        for (Computer computer : Jenkins.getActiveInstance().getComputers()) {
            if (computer instanceof ShareableComputer) {
                ShareableComputer shareableComputer = (ShareableComputer) computer;
                for (Executor executor : shareableComputer.getExecutors()) {
                    Queue.Executable executable = executor.getCurrentExecutable();
                    if (executable instanceof ReservationTask.ReservationExecutable) {
                        ReservationTask.ReservationExecutable rex = (ReservationTask.ReservationExecutable) executable;
                        ExecutorJenkins owner = rex.getParent().getOwner();
                        List<ReservationTask.ReservationExecutable> list = all.get(owner);
                        if (list == null) {
                            list = new ArrayList<>();
                            all.put(owner, list);
                        }
                        list.add(rex);
                        // Make sure executors no longer in config repo yet still occupying hosts are added
                        jenkinses.add(owner);
                    }
                }
            }
        }

        return all;
    }
}

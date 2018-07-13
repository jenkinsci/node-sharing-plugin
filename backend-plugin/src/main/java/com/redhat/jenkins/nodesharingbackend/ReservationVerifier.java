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
import com.redhat.jenkins.nodesharing.ActionFailed;
import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.util.CopyOnWriteMap;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.logging.Level;
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
 *     <li>A1: All nodes agree the host is idle,</li>
 *     <li>A2: Orchestrator agree with the only executor that claims the host.</li>
 * </ul>
 * No action needed here as grid is in sync.
 *
 * <h2>No collision</h2>
 *
 * <ul>
 *     <li>NC1: Orchestrator tracks a reservation for executor that does not report the host being reserved. UC: Executor failover or Missed returnNode call</li>
 *     <li>NC2: Orchestrator tracks no reservation for the host yet one executor claims it. UC: Orchestrator failover.</li>
 *     <li>NC3: Orchestrator tracks reservation of a host for executor X that does not report it while executor Y does. Bug or Race Condition</li>
 * </ul>
 * State representation of the Orchestrator is adjusted to match the one of the grid.
 *
 * <h2>Collision</h2>
 *
 * <ul>
 *     <li>C1: Orchestrator tracks reservation but extra executors report usage of the host. Bug or Race condition.</li>
 *     <li>C2: Multiple executors report reservation but orchestrator tracks none. Bug or Race condition.</li>
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
        return Functions.getIsUnitTest() ? Integer.MAX_VALUE : 5 * MIN;
    }

    @Override
    public void doRun() {
        ConfigRepo.Snapshot config;
        try {
            config = Pool.getInstance().getConfig();
        } catch (Pool.PoolMisconfigured ex) {
            return; // NOOP if not configured
        }

        verify(config, Api.getInstance());
    }

    @VisibleForTesting
    public static void verify(ConfigRepo.Snapshot config, Api api) {
        // When executor is removed from config repo, it might have ReservationTasks running for a while so it is
        // necessary to query these executors so the task completion can be detected.
        Set<ExecutorJenkins> jenkinses = new HashSet<>(config.getJenkinses());
        Map<ExecutorJenkins, Map<String, ReservationTask.ReservationExecutable>> trackedReservations = trackedReservations(jenkinses);
        Map<ExecutorJenkins, Set<String>> executorReservations = queryExecutorReservations(jenkinses, api);
        assert executorReservations.keySet().equals(trackedReservations.keySet());

        // TODO verify multiple executors are not using same host
        // TODO the executor might no longer use the plugin

        for (Map.Entry<ExecutorJenkins, Set<String>> er: executorReservations.entrySet()) {
            ExecutorJenkins executor = er.getKey();
            Collection<String> utilizedNodes = er.getValue();

            Collection<String> reservedNodes = trackedReservations.get(executor).keySet();

            if (utilizedNodes.equals(reservedNodes)) continue; // In sync

            ArrayList<String> toSchedule = new ArrayList<>(utilizedNodes);
            toSchedule.removeAll(reservedNodes);

            ArrayList<String> toCancel = new ArrayList<>(reservedNodes);
            toCancel.removeAll(utilizedNodes);

            // NC1
            for (String cancel : toCancel) {
                ReservationTask.ReservationExecutable reservation = trackedReservations.get(executor).get(cancel);
                // TODO: prone to race condition - the reservation may not yet created a computer
                ReservationTask parent = reservation.getParent();
                LOGGER.info("Cancelling dangling reservation for " + reservation.getNodeName() + " and " + parent.getName());
                reservation.complete();
            }

            // NC2
            for (String host : toSchedule) {
                // TODO the info from executor might have completed since we asked - should not create a new reservation
                LOGGER.info("Starting backfill reservation for " + host + " and " + executor.getName());
                new ReservationTask(executor, host, true).schedule().getFuture().getStartCondition();
            }
        }
    }

    private static @Nonnull Map<ExecutorJenkins, Set<String>> queryExecutorReservations(
            @Nonnull Set<ExecutorJenkins> jenkinses, @Nonnull Api api
    ) {
        Map<ExecutorJenkins, Set<String>> responses = new HashMap<>();
        for (ExecutorJenkins executorJenkins : jenkinses) {
            try {
                responses.put(executorJenkins, new HashSet<>(api.reportUsage(executorJenkins).getUsedNodes()));
            } catch (ActionFailed e) {
                LOGGER.log(Level.SEVERE, "Jenkins master '" + executorJenkins + "' didn't respond correctly:", e);
            }
        }
        return responses;
    }

    /**
     * Get mapping between served Executor Jenkinses and their current reservations.
     *
     * Executors that does not utilize any node will be reported with empty mapping.
     */
    private static @Nonnull Map<ExecutorJenkins, Map<String, ReservationTask.ReservationExecutable>> trackedReservations(Set<ExecutorJenkins> jenkinses) {
        Map<ExecutorJenkins, Map<String, ReservationTask.ReservationExecutable>> all = new HashMap<>();
        for (ExecutorJenkins jenkins : jenkinses) {
            // Make sure tracked executors without running reservation will be reported with empty mapping
            all.put(jenkins, new HashMap<String, ReservationTask.ReservationExecutable>());
        }

        for (ReservationTask.ReservationExecutable rex: ShareableComputer.getAllReservations().values()) {
            if (rex == null) continue;
            ExecutorJenkins owner = rex.getParent().getOwner();
            Map<String, ReservationTask.ReservationExecutable> list = all.get(owner);
            if (list == null) {
                list = new HashMap<>();
                all.put(owner, list);
            }
            list.put(rex.getNodeName(), rex);
            // Make sure executors no longer in config repo yet still occupying hosts are added
            jenkinses.add(owner);
        }

        return all;
    }
}

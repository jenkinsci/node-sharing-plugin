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
import hudson.model.PeriodicWork;
import jenkins.model.Jenkins;
import org.apache.commons.collections.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
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
        Map<ExecutorJenkins, PlannedFixup> plan = PlannedFixup.reduce(
                computePlannedFixup(config, api),
                computePlannedFixup(config, api)
        );

        for (Map.Entry<ExecutorJenkins, PlannedFixup> e2pf : plan.entrySet()) {
            ExecutorJenkins executor = e2pf.getKey();
            List<String> toSchedule = e2pf.getValue().toSchedule;
            List<String> toCancel = e2pf.getValue().toCancel;

            // NC1
            for (String cancel : toCancel) {
                ShareableComputer computer;
                try {
                    computer = ShareableComputer.getByName(cancel);
                } catch (NoSuchElementException e) {
                    continue;
                }
                ReservationTask.ReservationExecutable reservation = computer.getReservation();

                if (reservation == null) continue;
                ReservationTask parent = reservation.getParent();
                if (!parent.getOwner().equals(executor)) continue;

                LOGGER.info("Cancelling dangling reservation for " + reservation.getNodeName() + " and " + parent.getName());
                reservation.complete();
            }

            // NC2
            for (String host : toSchedule) {
                try {
                    ShareableComputer computer = ShareableComputer.getByName(host);
                    LOGGER.info("Starting backfill reservation for " + host + " and " + executor.getName());
                    ReservationTask.ReservationExecutable reservation = computer.getReservation();
                    if (reservation != null) {
                        ExecutorJenkins owner = reservation.getParent().getOwner();
                        if (owner.equals(executor)) continue;
                        LOGGER.warning("Host " + host + " is already reserved by " + owner.getName());
                    }

                    new ReservationTask(executor, host, true).schedule();
                } catch (NoSuchElementException ex) {
                    continue; // host disappeared
                }
            }
        }
    }

    private static Map<ExecutorJenkins, PlannedFixup> computePlannedFixup(ConfigRepo.Snapshot config, Api api) {
        // When executor is removed from config repo, it might have ReservationTasks running for a while so it is
        // necessary to query these executors so the task completion can be detected.
        Set<ExecutorJenkins> jenkinses = new HashSet<>(config.getJenkinses());
        Map<ExecutorJenkins, Map<String, ReservationTask.ReservationExecutable>> trackedReservations = trackedReservations(jenkinses);
        Map<ExecutorJenkins, Set<String>> executorReservations = queryExecutorReservations(jenkinses, api);
        assert executorReservations.keySet().equals(trackedReservations.keySet());

        // TODO verify multiple executors are not using same host
        // TODO the executor might no longer use the plugin

        Map<ExecutorJenkins, PlannedFixup> plan = new HashMap<>();
        for (Map.Entry<ExecutorJenkins, Set<String>> er: executorReservations.entrySet()) {
            ExecutorJenkins executor = er.getKey();
            Collection<String> utilizedNodes = er.getValue();

            Collection<String> reservedNodes = trackedReservations.get(executor).keySet();

            if (utilizedNodes.equals(reservedNodes)) continue; // In sync

            ArrayList<String> toSchedule = new ArrayList<>(utilizedNodes);
            toSchedule.removeAll(reservedNodes);

            ArrayList<String> toCancel = new ArrayList<>(reservedNodes);
            toCancel.removeAll(utilizedNodes);

            plan.put(executor, new PlannedFixup(toCancel, toSchedule));
        }

        return plan;
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

    /**
     * Planned actions to take for Executor Jenkins to bring it back in sync with orchestrator.
     */
    @VisibleForTesting
    /*package*/ static final class PlannedFixup {
        private final List<String> toCancel;
        private final List<String> toSchedule;

        /*package*/ PlannedFixup(List<String> toCancel, List<String> toSchedule) {
            if (toCancel == null || toSchedule == null) throw new IllegalArgumentException();
            if (CollectionUtils.containsAny(toCancel, toSchedule)) throw new IllegalArgumentException(
                    "List to-cancel and to-schedule overlap"
            );
            this.toCancel = toCancel;
            this.toSchedule = toSchedule;
        }

        /**
         * Merge several plans computed at different time together keeping the actions that are present in all the plans.
         * This is to separate the long-lasting problems that did not corrected itself from race conditions and minor glitches.
         */
        /*package*/ static PlannedFixup reduce(PlannedFixup... pf) {
            if (pf == null || pf.length <= 1) throw new IllegalArgumentException();

            ArrayList<String> rCancel = new ArrayList<>(pf[0].toCancel);
            ArrayList<String> rSchedule = new ArrayList<>(pf[0].toSchedule);
            for (int i = 1; i < pf.length; i++) {
                rCancel.retainAll(pf[i].toCancel);
                rSchedule.retainAll(pf[i].toSchedule);
            }

            return new PlannedFixup(rCancel, rSchedule);
        }

        /*package*/ static Map<ExecutorJenkins, PlannedFixup> reduce(Map<ExecutorJenkins, PlannedFixup>... samples) {
            if (samples == null || samples.length <= 1) throw new IllegalArgumentException();

            ArrayList<ExecutorJenkins> jenkinsesWithPlanForEverySample = new ArrayList<>(samples[0].keySet());
            for (int i = 1; i < samples.length; i++) {
                jenkinsesWithPlanForEverySample.retainAll(samples[i].keySet());
            }

            Map<ExecutorJenkins, List<PlannedFixup>> collectedPlans = new HashMap<>();
            for (ExecutorJenkins ej: jenkinsesWithPlanForEverySample) {
                collectedPlans.put(ej, new ArrayList<PlannedFixup>());
            }

            for (Map<ExecutorJenkins, PlannedFixup> planSample : samples) {
                Set<ExecutorJenkins> jenkinses = planSample.keySet();
                // Remove all plans for all Executors that do not have plans in all samples
                jenkinses.retainAll(jenkinsesWithPlanForEverySample);

                // Collect plans per-executor
                for (Map.Entry<ExecutorJenkins, PlannedFixup> executorPlanSample: planSample.entrySet()) {
                    collectedPlans.get(executorPlanSample.getKey()).add(executorPlanSample.getValue());
                }
            }

            Map<ExecutorJenkins, PlannedFixup> finalPlan = new HashMap<>();
            for (Map.Entry<ExecutorJenkins, List<PlannedFixup>> cp : collectedPlans.entrySet()) {
                finalPlan.put(cp.getKey(), PlannedFixup.reduce(cp.getValue().toArray(new PlannedFixup[0])));
            }
            return finalPlan;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PlannedFixup that = (PlannedFixup) o;
            return Objects.equals(toCancel, that.toCancel) && Objects.equals(toSchedule, that.toSchedule);
        }

        @Override public int hashCode() {
            return Objects.hash(toCancel, toSchedule);
        }
    }
}

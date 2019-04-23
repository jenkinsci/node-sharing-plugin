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
package com.redhat.jenkins.nodesharingfrontend;

import com.google.common.annotations.VisibleForTesting;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Report executor Queue workload to orchestrator periodically.
 *
 * @author ogondza.
 */
@Extension
@Restricted(NoExternalUse.class)
public class WorkloadReporter extends PeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(WorkloadReporter.class.getName());

    @Override
    public long getRecurrencePeriod() {
        return 3 * MIN;
    }

    @Override
    @VisibleForTesting
    public void doRun() {
        assert Jenkins.getAuthentication() == ACL.SYSTEM: "Must be called as SYSTEM, not " + Jenkins.getAuthentication();

        Map<SharedNodeCloud, ReportWorkloadRequest.Workload.WorkloadBuilder> workloadMapping = new HashMap<>();
        for (SharedNodeCloud cloud : SharedNodeCloud.getAll()) {

            if (!cloud.isActive()) {
                LOGGER.fine("Skipping cloud " + cloud.name + " as it is not declared in config repo: " + cloud.getConfigRepoUrl());
                continue;
            }

            // Create empty workload for every cloud to make sure clouds we have no workload for will receive empty workload
            ReportWorkloadRequest.Workload.WorkloadBuilder workload = ReportWorkloadRequest.Workload.builder();
            workloadMapping.put(cloud, workload);
        }

        // Fill only if Jenkins isn't going to restart, report empty workload otherwise
        if (!Jenkins.getInstance().isQuietingDown() && !Jenkins.getInstance().isTerminating()) {
            // Make sure those scheduled sooner are at the beginning
            List<Queue.BuildableItem> items = Jenkins.getInstance().getQueue().getBuildableItems();
            for (Queue.Item item : items) {
                if ("com.redhat.jenkins.nodesharingbackend.ReservationTask".equals(item.task.getClass().getName())) {
                    // TEST HACK: these are not supposed to coexist but they do in jth-tests
                    continue;
                }

                for (Map.Entry<SharedNodeCloud, ReportWorkloadRequest.Workload.WorkloadBuilder> e : workloadMapping.entrySet()) {
                    SharedNodeCloud cloud = e.getKey();
                    ReportWorkloadRequest.Workload.WorkloadBuilder workload = e.getValue();
                    if (cloud.canProvision(item.getAssignedLabel())) {
                        workload.addItem(item);
                    }
                }
            }
        }

        for (Map.Entry<SharedNodeCloud, ReportWorkloadRequest.Workload.WorkloadBuilder> entry : workloadMapping.entrySet()) {
            ReportWorkloadRequest.Workload.WorkloadBuilder workload = entry.getValue();
            SharedNodeCloud cloud = entry.getKey();
            cloud.getApi().reportWorkload(workload.build());
        }
    }

    /**
     * Schedule reportWorkload call for near future once buildable items change. Ignore all changes until the time the
     * push takes place.
     */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class Detector extends QueueListener {
        private volatile Future<?> nextPush;

        public static @Nonnull Detector getInstance() {
            ExtensionList<Detector> list = Jenkins.getInstance().getExtensionList(Detector.class);
            assert list.size() == 1;
            return list.iterator().next();
        }

        @Inject
        private WorkloadReporter wr;

        // We need this for predictable authentication and Exception handling
        private SafeTimerTask safeTimerTask = new SafeTimerTask() {
            @Override
            protected void doRun() {
                nextPush = null;
                wr.doRun();
            }
        };

        @Override
        public void onEnterBuildable(Queue.BuildableItem bi) {
            scheduleUpdate();
        }

        @Override
        public void onLeaveBuildable(Queue.BuildableItem bi) {
            scheduleUpdate();
        }

        @Override
        public void onLeft(Queue.LeftItem li) {
            scheduleUpdate();
        }

        /* package */ void scheduleUpdate() {
            // Can be done or canceled in case of a bug or external intervention - do not allow it to hang there forever
            if (nextPush != null && !(nextPush.isDone() || nextPush.isCancelled())) return;
            nextPush = Timer.get().schedule(safeTimerTask, 10, TimeUnit.SECONDS);
        }

        public void update() {
            safeTimerTask.run();
        }
    }
}

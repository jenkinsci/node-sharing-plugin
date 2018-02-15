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
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Report executor Queue workload to orchestrator periodically.
 *
 * @author ogondza.
 */
@Extension
@Restricted(NoExternalUse.class)
public class WorkloadReporter extends PeriodicWork {

    @Override
    public long getRecurrencePeriod() {
        return 3 * MIN;
    }

    @Override @VisibleForTesting
    public void doRun() {
        Map<SharedNodeCloud, ReportWorkloadRequest.Workload> workloadMapping = new HashMap<>();
        // Make sure those scheduled sooner are at the beginning
        List<Queue.BuildableItem> items = Jenkins.getActiveInstance().getQueue().getBuildableItems();

        for (Queue.Item item : items) {
            if ("com.redhat.jenkins.nodesharingbackend.ReservationTask".equals(item.task.getClass().getName())) {
                // TEST HACK: these are not supposed to coexist but they do in jth-tests
                continue;
            }

            for (SharedNodeCloud cloud : SharedNodeCloud.getAll()) {
                if (cloud.canProvision(item.getAssignedLabel())) {
                    ReportWorkloadRequest.Workload workload = workloadMapping.get(cloud);
                    if (workload == null) {
                        workload = new ReportWorkloadRequest.Workload();
                        workloadMapping.put(cloud, workload);
                    }

                    workload.addItem(item);
                }
            }
        }

        // TODO do not resend the same data to decrease the load
        // PJ: Actually current behavior is expected from Orchestrator side (after crash/restart etc.)
        for (Map.Entry<SharedNodeCloud, ReportWorkloadRequest.Workload> entry : workloadMapping.entrySet()) {
            ReportWorkloadRequest.Workload workload = entry.getValue();
            SharedNodeCloud cloud = entry.getKey();
            cloud.getApi().reportWorkload(workload);
        }
    }

    /**
     * Schedule reportWorkload call for near future once buildable items change. Ignore all changes until the time the
     * push takes place.
     */
    @Extension @Restricted(NoExternalUse.class)
    public static final class Detector extends QueueListener implements Runnable {
        private volatile Future<?> nextPush;

        @Inject WorkloadReporter wr;

        @Override
        public void onEnterBuildable(Queue.BuildableItem bi) {
            push();
        }

        @Override
        public void onLeaveBuildable(Queue.BuildableItem bi) {
            push();
        }

        @Override
        public void onLeft(Queue.LeftItem li) {
            push();
        }

        private void push() {
            // Can be done or canceled in case of a bug or external intervention - do not allow it to hang there forever
            if (nextPush != null && !(nextPush.isDone() || nextPush.isCancelled())) return;
            nextPush = Timer.get().schedule(this, 10, TimeUnit.SECONDS);
        }

        @Override
        public void run() {
            nextPush = null;
            wr.doRun();
        }
    }
}

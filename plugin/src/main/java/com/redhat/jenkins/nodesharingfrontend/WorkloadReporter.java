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
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.util.HashMap;
import java.util.Map;

/**
 * Report executor Queue workload to orchestrator periodically.
 *
 * @author ogondza.
 */
// TODO periodic reporting on is probably not sufficient in the long run as there is no good compromise between prompt
// provisioning and not hammering the backend. I would probably be for the best if:
// - sudden load can generate update
// - queue will be rechecked even when no listener was called
// - the same workload will not be send repeatedly
@Extension
@Restricted(NoExternalUse.class)
public class WorkloadReporter extends PeriodicWork {

    @Override
    public long getRecurrencePeriod() {
        return 3 * MIN;
    }

    @Override @VisibleForTesting
    public void doRun() throws Exception {
        Map<SharedNodeCloud, ReportWorkloadRequest.Workload> workloadMapping = new HashMap<>();
        Queue.Item[] items = Jenkins.getActiveInstance().getQueue().getItems();
        for (Queue.Item item : items) {
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

        // TODO do not resend the same data
        for (Map.Entry<SharedNodeCloud, ReportWorkloadRequest.Workload> entry : workloadMapping.entrySet()) {
            ReportWorkloadRequest.Workload workload = entry.getValue();
            SharedNodeCloud cloud = entry.getKey();
            cloud.getApi().reportWorkload(workload);
        }
    }
}

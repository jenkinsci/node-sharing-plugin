package com.redhat.jenkins.nodesharing.transport;

import org.apache.commons.collections.CollectionUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.arrayWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class ReportWorkloadTest {

    @Test
    public void requestRoundtrip() throws Exception {
        List<ReportWorkloadRequest.Workload.WorkloadItem> lwi = new ArrayList<ReportWorkloadRequest.Workload.WorkloadItem>();
        lwi.add(new ReportWorkloadRequest.Workload.WorkloadItem(1, "Item 1"));
        lwi.add(new ReportWorkloadRequest.Workload.WorkloadItem(2, "Item 2"));
        ReportWorkloadRequest.Workload w = new ReportWorkloadRequest.Workload(lwi);

        ReportWorkloadRequest sent = new ReportWorkloadRequest(new ExecutorEntity.Fingerprint("configRepo", "4.2",
                "my-executor"), w);
        ReportWorkloadRequest received = Entity.fromString(sent.toString(), ReportWorkloadRequest.class);
        assertEquals(sent.getConfigRepoUrl(), received.getConfigRepoUrl());
        assertEquals(sent.getVersion(), received.getVersion());
        assertEquals(sent.getExecutorName(), received.getExecutorName());
        assertThat(
                CollectionUtils.retainAll(sent.getWorkload().getItems(), received.getWorkload().getItems()).toArray(),
                arrayWithSize(0)
        );
    }
}

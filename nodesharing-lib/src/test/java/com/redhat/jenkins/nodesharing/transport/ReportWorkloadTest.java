package com.redhat.jenkins.nodesharing.transport;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class ReportWorkloadTest {

    @Test
    public void requestRoundtrip() throws Exception {
        List<ReportWorkloadRequest.Workload.WorkloadItem> lwi = new ArrayList<>();
        lwi.add(new ReportWorkloadRequest.Workload.WorkloadItem(1, "Item 1", "foo&&bar"));
        lwi.add(new ReportWorkloadRequest.Workload.WorkloadItem(2, "Item 2", "dead||beaf"));
        ReportWorkloadRequest.Workload w = new ReportWorkloadRequest.Workload(lwi);

        ExecutorEntity.Fingerprint fingerprint = new ExecutorEntity.Fingerprint("configRepo", "4.2", "my-executor");
        ReportWorkloadRequest sent = new ReportWorkloadRequest(fingerprint, w);
        ReportWorkloadRequest received = Entity.fromString(sent.toString(), ReportWorkloadRequest.class);
        assertEquals(sent.getConfigRepoUrl(), received.getConfigRepoUrl());
        assertEquals(sent.getVersion(), received.getVersion());
        assertEquals(sent.getExecutorUrl(), received.getExecutorUrl());
        assertThat(sent.getWorkload().getItems(), equalTo(received.getWorkload().getItems()));
    }
}

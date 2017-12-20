package com.redhat.jenkins.nodesharing.transport;

import static org.junit.Assert.*;

import com.redhat.jenkins.nodesharing.NodeDefinition;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.util.Collections;

/**
 * @author ogondza.
 */
public class DiscoverTest {

    @Test
    public void requestRoundtrip() throws Exception {
        DiscoverRequest sent = new DiscoverRequest(new ExecutorEntity.Fingerprint("configRepo", "4.2", "my-executor"));
        DiscoverRequest received = Entity.fromString(sent.toString(), DiscoverRequest.class);
        assertEquals(sent.getConfigRepoUrl(), received.getConfigRepoUrl());
        assertEquals(sent.getVersion(), received.getVersion());
        assertEquals(sent.getExecutorName(), received.getExecutorName());
    }

    @Test
    public void responseRoundtrip() throws Exception {
        NodeDefinition node = new NodeDefinition("foo.xml", "<definition/>") {
            @Override public String getName() {
                return "fake-name";
            }

            @Nonnull @Override public String getLabel() {
                return "my fake label atoms";
            }
        };
        DiscoverResponse sent = new DiscoverResponse("configRepo", "4.2", "diagnosis", Collections.singleton(node));
        DiscoverResponse received = Entity.fromString(sent.toString(), DiscoverResponse.class);
        assertEquals(sent.getConfigRepoUrl(), received.getConfigRepoUrl());
        assertEquals(sent.getVersion(), received.getVersion());
        assertEquals(sent.getLabels(), received.getLabels());
        assertEquals(sent.getDiagnosis(), received.getDiagnosis());
    }
}

package com.redhat.jenkins.nodesharingbackend;

import static org.junit.Assert.*;

import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * @author ogondza.
 */
public class ReservationTaskTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    private static final ExecutorJenkins ACME_EXECUTOR = new ExecutorJenkins("http://acme.com", "acme");
    private static final ExecutorJenkins EMCA_EXECUTOR = new ExecutorJenkins("http://emca.com", "emca");

    @Test
    public void essentials() {
        ReservationTask id = new ReservationTask(ACME_EXECUTOR, label("foo"), "bar", 42);
        assertEquals(id, id);
        assertEquals(id.hashCode(), id.hashCode());

        assertNotEquals(id, new ReservationTask(ACME_EXECUTOR, label("foo"), "bar", 11));
        assertNotEquals(id, new ReservationTask(ACME_EXECUTOR, label("foo"), "BUZ", 42));
        assertNotEquals(id, new ReservationTask(ACME_EXECUTOR, label("UFO"), "bar", 42));
        assertNotEquals(id, new ReservationTask(EMCA_EXECUTOR, label("foo"), "bar", 42));

        ReservationTask backfill = new ReservationTask(ACME_EXECUTOR, "foo", true);
        assertEquals(backfill, backfill);
        assertEquals(backfill.hashCode(), backfill.hashCode());

        assertNotEquals(backfill, new ReservationTask(EMCA_EXECUTOR, "foo", true));
        assertNotEquals(backfill, new ReservationTask(ACME_EXECUTOR, "bar", true));

        // Backfills are never equal to non-backfills
        assertNotEquals(
                new ReservationTask(ACME_EXECUTOR, "host", true),
                new ReservationTask(ACME_EXECUTOR, label("host"), "host", -1)
        );
    }

    private Label label(String foo) {
        return Label.get(foo);
    }
}

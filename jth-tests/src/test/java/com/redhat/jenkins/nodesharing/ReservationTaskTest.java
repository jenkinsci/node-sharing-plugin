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
package com.redhat.jenkins.nodesharing;

import com.gargoylesoftware.htmlunit.Page;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharing.utils.NodeSharingJenkinsRule;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;

public class ReservationTaskTest {
    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Test
    public void linkIsClickable() throws Exception {
        j.addSharedNodeCloud(Pool.getInstance().getConfigRepoUrl());
        j.singleJvmGrid(j.jenkins);

        BlockingBuilder running = j.getBlockingProject("solaris11");
        running.schedule();
        running.start.block();
        BlockingBuilder queued = j.getBlockingProject("solaris11");
        queued.schedule();
        Thread.sleep(100); // Wait until build is queued before reporting workload
        j.reportWorkloadToOrchestrator();

        assertThat(j.getActiveReservations().size(), equalTo(1));
        assertThat(j.getQueuedReservations().size(), equalTo(1));

        ReservationTask.ReservationExecutable active = j.getActiveReservations().iterator().next();
        ReservationTask waiting = j.getQueuedReservations().iterator().next();

        // Locating the links in AJAX contributed panes is a mayor pain - gave up
        JenkinsRule.WebClient wc = j.createWebClient().login("admin", "admin");
        String activeUrl = active.getParent().getUrl();
        assertThat(activeUrl, not(startsWith("/")));
        Page target = wc.getPage(j.getURL() + activeUrl);
        assertEquals(j.getURL(), target.getUrl());
        String waitingUrl = waiting.getUrl();
        assertThat(waitingUrl, not(startsWith("/")));
        target = wc.getPage(j.getURL() + waitingUrl);
        assertEquals(j.getURL(), target.getUrl());
    }
}

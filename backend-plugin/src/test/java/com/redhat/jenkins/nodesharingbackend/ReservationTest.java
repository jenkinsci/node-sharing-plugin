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

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import hudson.model.Label;
import hudson.model.Queue;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.URL;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class ReservationTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Test
    public void runBuildSuccessfully() throws Exception {
        j.injectDummyConfigRepo();

        // When I schedule a bunch of tasks
        // TODO replace with API#reportUsage()
        ReservationTask windows = new ReservationTask(j.DUMMY_OWNER, Label.get("windows"));
        windows.schedule().getFuture().getStartCondition().get();
        ReservationTask s1 = new ReservationTask(j.DUMMY_OWNER, Label.get("solaris11"));
        s1.schedule().getFuture().getStartCondition().get();
        ReservationTask s2 = new ReservationTask(j.DUMMY_OWNER, Label.get("solaris11"));
        Queue.Item queuedItem = s2.schedule();

        // They start occupying the hosts they should or stay in queue
        assertSame(s1, j.getComputer("solaris1.acme.com").getReservation().getParent());
        ReservationTask.ReservationExecutable winReservation = j.getComputer("win1.acme.com").getReservation();
        if (winReservation == null) {
            winReservation = j.getComputer("win2.acme.com").getReservation();
        }
        assertSame(windows, winReservation.getParent());
        assertFalse(queuedItem.getFuture().getStartCondition().isDone());

        // When first client returns its host
        Api api = Api.getInstance();
        JenkinsRule.WebClient wc = j.createWebClient();
         String url = wc.createCrumbedUrl(api.getUrlName() + "/returnNode") + "&name=solaris1.acme.com&owner=" + j.DUMMY_OWNER.getUrl() + "&status=OK";
        WebRequest request = new WebRequest(new URL(url), HttpMethod.POST);

        wc.getPage(request);

        // Queued reservation is now executed
        queuedItem.getFuture().getStartCondition().get();
        assertSame(s2, j.getComputer("solaris1.acme.com").getReservation().getParent());
    }
}

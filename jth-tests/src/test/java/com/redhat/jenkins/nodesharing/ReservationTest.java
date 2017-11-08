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

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.util.OneShotEvent;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestBuilder;

import java.io.IOException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReservationTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test @Ignore // TODO Keep hacking until this passes
    public void runBuildSuccessfully() throws Exception {
        j.injectConfigRepo(configRepo.create(getClass().getResource("dummy_config_repo")));

        // When I schedule a bunch of tasks
        Label winLabel = Label.get("w2k12");
        FreeStyleProject winJob = j.createFreeStyleProject();
        winJob.setAssignedLabel(winLabel);
        BlockingBuilder winBuilder = new BlockingBuilder();
        winJob.getBuildersList().add(winBuilder);
        Label solarisLabel = Label.get("solaris11&&!(x86||x86_64)");
        FreeStyleProject solarisJob = j.createFreeStyleProject();
        solarisJob.setAssignedLabel(solarisLabel);
        BlockingBuilder solarisBuilder = new BlockingBuilder();
        solarisJob.getBuildersList().add(solarisBuilder);

        winJob.scheduleBuild2(0).getStartCondition().get();
        solarisJob.scheduleBuild2(0).getStartCondition().get();
        Future<FreeStyleBuild> scheduledSoalrisRun = solarisJob.scheduleBuild2(0).getStartCondition();
        assertFalse(scheduledSoalrisRun.isDone());

        // They start occupying real computers or they stay in the queue
        assertEquals(solarisLabel, j.getComputer("solaris1.executor.com").getReservation().getParent().getAssignedLabel());
        assertEquals(winLabel, j.getComputer("win1.executor.com").getReservation().getParent().getAssignedLabel());
        assertNull(j.getComputer("win2.executor.com").getReservation());
        assertFalse(scheduledSoalrisRun.isDone());

        winBuilder.end.signal();
        j.assertBuildStatusSuccess(winJob.getBuildByNumber(1));
        assertNull(j.getComputer("win1.executor.com").getReservation());
        assertNull(j.getComputer("win2.executor.com").getReservation());

        // When first solaris task completes
        solarisBuilder.end.signal();
        j.assertBuildStatusSuccess(solarisJob.getBuildByNumber(1));
        scheduledSoalrisRun.get();
        assertTrue("Blocked task should resume", scheduledSoalrisRun.isDone());
        assertNotNull(j.getComputer("solaris1.executor.com").getReservation());
    }

    private static final class BlockingBuilder extends TestBuilder {
        private OneShotEvent start = new OneShotEvent();
        private OneShotEvent end = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            start.signal();
            end.block();
            return true;
        }
    }
}

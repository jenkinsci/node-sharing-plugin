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

import com.redhat.jenkins.nodesharing.ConfigRepo;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.View;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

/**
 * Main view of the orchestrator.
 */
@Restricted(NoExternalUse.class)
public class Dashboard extends View {
    // It is quite delicate when this is invoked to fit between primary view updates hardcoded in Jenkins class itself
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void registerDashboard() throws IOException {
        Jenkins j = Jenkins.getActiveInstance();
        Dashboard dashboard = new Dashboard();
        j.addView(dashboard);
        j.setPrimaryView(dashboard);
    }

    public Dashboard() {
        super("Node Sharing Pool Orchestrator");
    }

    public @Nonnull ConfigRepo.Snapshot getConfigSnapshot() {
        return Pool.getInstance().getConfig();
    }

    // Reservation tasks URLs are limited to orchestrator local. This is here to redirect to Executor Jenkins
    public void doRedirectToExecutor(StaplerRequest req) {
        // Cannot use multiple query parameters as output of `ReservationTask#getUrl()` gets escaped breaking `&`
        // Expected `/<EXECUTOR>/<NODE>`
        String[] split = req.getRestOfPath().split("/");
        assert split.length == 3;
        assert split[0].isEmpty();

        String executorName = split[1];
        String nodeName = split[2];

        Jenkins.checkGoodName(nodeName);
        ConfigRepo.Snapshot snapshot = getConfigSnapshot();
        String executorUrl = snapshot.getJenkinsByName(executorName).getUrl().toExternalForm();
        if (snapshot.getNodes().containsKey(nodeName)) {
            throw HttpResponses.redirectTo(executorUrl + "/computer/" + nodeName + "/");
        }

        throw HttpResponses.redirectToContextRoot();
    }

    // Satisfy the view interface

    @Override public Collection<TopLevelItem> getItems() { return Collections.emptyList(); }
    @Override public boolean contains(TopLevelItem item) { return false; }
    @Override protected void submit(StaplerRequest req) { throw HttpResponses.status(SC_BAD_REQUEST); }
    @Override public Item doCreateItem(StaplerRequest req, StaplerResponse rsp) { throw HttpResponses.status(SC_BAD_REQUEST); }
}

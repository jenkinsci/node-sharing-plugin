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

import com.redhat.jenkins.nodesharing.transport.DiscoverResponse;
import com.redhat.jenkins.nodesharing.transport.ReportWorkloadRequest;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharingbackend.Pool.Updater;
import com.redhat.jenkins.nodesharingfrontend.Api;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;

import javax.servlet.http.HttpServletResponse;

import static com.redhat.jenkins.nodesharingbackend.Pool.getInstance;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RemovingExecutorFromConfigRepo {

    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();
    @Rule public LoggerRule l = new LoggerRule();

    @Test
    public void rejectDiscoverFromExecutorNotInConfigRepo() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        removeExecutor(gitClient);

        String configRepoUrl = getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);

        Api api = cloud.getApi();

        DiscoverResponse discover = api.discover();
        assertThat(discover.getLabels(), Matchers.<String>emptyIterable());
        assertThat(discover.getDiagnosis(), containsString("Jenkins '" + j.getURL() + "' is not declared as a member of the pool in " + configRepoUrl));
    }

    @Test
    public void acceptDoReturnNodeFromExecutor() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);

        String configRepoUrl = getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("solaris11"));
        BlockingBuilder bb = new BlockingBuilder();
        p.getBuildersList().add(bb);
        FreeStyleBuild b = p.scheduleBuild2(0).getStartCondition().get();
        bb.start.block();

        removeExecutor(gitClient);

        Api api = cloud.getApi();
        api.returnNode(((SharedNode) b.getBuiltOn()));

        bb.end.signal();
        j.waitUntilNoActivity();
    }

    @Test
    public void rejectWorkloadFromExecutorNotInConfigRepo() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        removeExecutor(gitClient);

        String configRepoUrl = getInstance().getConfigRepoUrl();
        SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);

        Api api = cloud.getApi();
        try {
            api.reportWorkload(ReportWorkloadRequest.Workload.builder().build());
            fail("Reporting workload should fail for executor not part of the pool");
        } catch (ActionFailed.RequestFailed ex) {
            assertEquals(HttpServletResponse.SC_CONFLICT, ex.getStatusCode());
        }
    }

    private void removeExecutor(GitClient gitClient) throws Exception {
        // Replace the inner Jenkins with one from different URL as removing the file would cause git to remove the empty
        // directory breaking repo validation
        j.writeJenkinses(gitClient, singletonMap("this-one", j.getURL() + "/defunc"));
        Updater.getInstance().doRun();
    }
}

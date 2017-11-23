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

import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharing.NodeSharingJenkinsRule.MockTask;
import hudson.model.Label;
import hudson.model.Node;
import org.jenkinsci.plugins.gitclient.GitClient;
import hudson.model.Queue;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class SharedNodeCloudTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test
    public void doTestConnection() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        final String configRepoUrl = gitClient.getWorkTree().getRemote();
        final Properties prop = new Properties();
        prop.load(this.getClass().getClassLoader().getResourceAsStream("nodesharingbackend.properties"));
        final SharedNodeCloud cloud = j.addSharedNodeCloud(configRepoUrl);
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(configRepoUrl).getMessage(),
                containsString("Orchestrator version is " + prop.getProperty("version"))
        );

    }

    @Test
    public void doTestConnectionInvalidUrl() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection("file:\\\\aaa").getMessage(),
                equalTo("Invalid config repo url")
        );
    }

    @Test
    public void doTestConnectionNonExistsUrl() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection("file://dummy_not_exists").getMessage(),
                equalTo("Unrecognized config repo content")
        );
    }

    @Test
    public void doTestConnectionImproperContentRepo() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        final SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection("file:///tmp").getMessage(),
                equalTo("Unrecognized config repo content")
        );
    }

    // TODO Implementation isn't completed
    @Ignore
    @Test
    public void doReportWorkloadTest() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.setCrumbIssuer(null);
        List<Queue.Item> qli = new ArrayList<Queue.Item>();
        MockTask task = new MockTask(j.DUMMY_OWNER, Label.get("solaris11"));
        Queue.Item item = task.schedule();
        qli.add(item);
        qli.add(item);

        System.out.println("Status: " + cloud.getApi().doReportWorkload(qli));
    }


    @Test
    public void nodeStatusTest() throws Exception {
        final GitClient gitClient = j.injectConfigRepo(configRepo.createReal(getClass().getResource("real_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());
        j.jenkins.setCrumbIssuer(null);
//        for (Node n : j.jenkins.getNodes()) {
//            System.out.println(n.getNodeName());
//        }
        assertThat(
                Communication.NodeState.getStatus((Integer) cloud.getApi().nodeStatus("foo")),
                equalTo(Communication.NodeState.NOT_FOUND)
        );
        assertThat(
                Communication.NodeState.getStatus((Integer) cloud.getApi().nodeStatus("solaris1.orchestrator")),
                equalTo(Communication.NodeState.FOUND)
        );
    }
}

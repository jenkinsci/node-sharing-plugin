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

import com.google.common.collect.Sets;
import com.redhat.jenkins.nodesharing.transport.ExecutorEntity;
import com.redhat.jenkins.nodesharing.transport.ReportUsageResponse;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ReservationVerifier;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static com.redhat.jenkins.nodesharingbackend.Pool.getInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ReportUsageTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test @Ignore
    public void reportExecutorUsage() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(getInstance().getConfigRepoUrl());
        cloud.getLatestConfig(); // NodeSharingComputerListener#preLaunch does not consider this to be operational until we have config

        ConfigRepo.Snapshot snapshot = getInstance().getConfig();
        Collection<NodeDefinition> declaredNodes = snapshot.getNodes().values();

        assertThat(j.getScheduledReservations(), emptyIterable());
        assertThat(j.getPendingReservations(), emptyIterable());
        assertThat(j.jenkins.getNodes(), Matchers.<Node>iterableWithSize(declaredNodes.size()));

        // Given all nodes shared for a single master
        for (NodeDefinition definition : declaredNodes) {
            SharedNode node = cloud.createNode(definition);
            j.jenkins.addNode(node);
            j.jenkins.getComputer(definition.getName()).waitUntilOnline();
        }
        assertThat(j.jenkins.getNodes(), Matchers.<Node>iterableWithSize(declaredNodes.size() * 2));

        for (int i = 0; i < 3; i++) {

            // When usage is reported
            ReservationVerifier.getInstance().doRun();

            // Then reservations are created
            assertThat(j.getPendingReservations().size(), equalTo(declaredNodes.size()));
            assertThat(j.getScheduledReservations(), emptyIterable());
        }

        // Cleanup to avoid all kinds of exceptions
        for (ReservationTask.ReservationExecutable executable : j.getPendingReservations()) {
            executable.complete();
        }
    }

    @Test
    public void orchestratorFailover() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(getInstance().getConfigRepoUrl());

        ConfigRepo.Snapshot c = cloud.getLatestConfig();
        ArrayList<String> nodes = new ArrayList<>(c.getNodes().keySet());

        ConfigRepo.Snapshot config = spy(c);
        when(config.getJenkinses()).thenReturn(Sets.newHashSet(
                new ExecutorJenkins(j.jenkins.getRootUrl(), "Fake one", getInstance().getConfigRepoUrl()),
                new ExecutorJenkins(j.jenkins.getRootUrl(), "Fake two", getInstance().getConfigRepoUrl())
        ));
        Api api = mock(Api.class);
        ExecutorEntity.Fingerprint fp = new ExecutorEntity.Fingerprint("", "", "");
        when(api.reportUsage(Mockito.any(ExecutorJenkins.class))).thenReturn(
                new ReportUsageResponse(fp, Arrays.asList(nodes.get(0), nodes.get(1))),
                new ReportUsageResponse(fp, Collections.singletonList(nodes.get(3)))
        );

        ReservationVerifier.verify(config, api);

        assertThat(j.getScheduledReservations(), emptyIterable());
        assertThat(j.getPendingReservations(), Matchers.<ReservationTask.ReservationExecutable>iterableWithSize(3));
    }

    @Test @Ignore
    public void missedReturnNodeCall() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(getInstance().getConfigRepoUrl());
        ConfigRepo.Snapshot c = cloud.getLatestConfig();

        ExecutorJenkins owner = c.getJenkinses().iterator().next();
        NodeDefinition node = c.getNodes().values().iterator().next();
        ReservationTask reservationTask = new ReservationTask(owner, Label.get(node.getLabel().replaceAll("[ ]+", "&&")), "foo");
        ReservationTask.ReservationExecutable executable = (ReservationTask.ReservationExecutable) reservationTask.schedule().getFuture().getStartCondition().get();

        assertThat(j.getScheduledReservations(), emptyIterable());
        assertThat(j.getPendingReservations(), Matchers.<ReservationTask.ReservationExecutable>iterableWithSize(1));

        // TODO As there is no need for the reservation, executor rejects it immediately
        assertThat(j.jenkins.getNodes().toString(), j.jenkins.getNodes().size(), equalTo(c.getNodes().size() + 1));

        executable.complete();
    }
}

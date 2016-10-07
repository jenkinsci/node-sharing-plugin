package com.redhat.foreman;
/*
 * The MIT License

 *
 * Copyright (c) 2016-
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


import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import javax.servlet.ServletException;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AdministrativeMonitor;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.util.OneShotEvent;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.redhat.foreman.ForemanSharedNodeCloud.DescriptorImpl;
import com.redhat.foreman.launcher.ForemanDummyComputerLauncherFactory;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Cause.UserIdCause;
import hudson.model.labels.LabelAtom;
import hudson.util.Secret;
import org.jvnet.hudson.test.TestBuilder;

/**
 * Cloud Unit Tests.
 *
 */
public class ForemanSharedNodeCloudTest {

    private static final int HTTPOK = 200;
    private static final int HTTPERROR = 500;

    private static final String URL = "http://localhost:32789/api";
    private static final String USER = "admin";
    private static final String PASSWORD = "changeme";

    /**
     * Rule for Jenkins.
     */
    @Rule
    //CS IGNORE VisibilityModifier FOR NEXT 2 LINES. REASON: Parent.
    public JenkinsRule j = new JenkinsRule();

    /**
     * Rule for wiremock.
     */
    @Rule
    //CS IGNORE VisibilityModifier FOR NEXT 2 LINES. REASON: Parent.
    public final WireMockRule wireMockRule = new WireMockRule(32789);

    /**
     * Test for configuration of a Foreman Cloud.
     * @throws Exception if occurs.
     */
    @Test
    public void testConfigRoundtrip() throws Exception {
        ForemanSharedNodeCloud orig = new ForemanSharedNodeCloud("mycloud", URL,
                USER, Secret.fromString(PASSWORD), "", 1);
        j.getInstance().clouds.add(orig);
        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        j.assertEqualBeans(orig, j.jenkins.clouds.iterator().next(),
                "cloudName,url,user,password,credentialsId");
    }

    /**
     * Prepare wiremocks.
     * @throws IOException if occurs.
     * @throws URISyntaxException if occurs.
     */
    private void setupWireMock() throws IOException, URISyntaxException {
        String body1  = TestUtils.readFile("body1.txt", StandardCharsets.UTF_8);
        String body2  = TestUtils.readFile("body2.txt", StandardCharsets.UTF_8);
        String body3  = TestUtils.readFile("body3.txt", StandardCharsets.UTF_8);
        String body4  = TestUtils.readFile("body4.txt", StandardCharsets.UTF_8);
        String body5  = TestUtils.readFile("body5.txt", StandardCharsets.UTF_8);
        String body6  = TestUtils.readFile("body6.txt", StandardCharsets.UTF_8);
        String body7  = TestUtils.readFile("body7.txt", StandardCharsets.UTF_8);
        String body8  = TestUtils.readFile("body8.txt", StandardCharsets.UTF_8);
        String body9  = TestUtils.readFile("body9.txt", StandardCharsets.UTF_8);
        String body10 = TestUtils.readFile("body10.txt", StandardCharsets.UTF_8);
        String body11 = TestUtils.readFile("body11.txt", StandardCharsets.UTF_8);

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body1)));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1+and+params.RESERVED%3D"
                + "false+and+has+params.JENKINS_SLAVE_REMOTEFS_ROOT"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body2)));

        stubFor(get(urlMatching("/api/hosts_reserve.+"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body3)));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlocalhost.localdomain"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body4)));

        stubFor(get(urlEqualTo("/api/hosts_release?query=name+~+localhost.localdomain"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body5)));

        stubFor(get(urlEqualTo("/api/v2/hosts"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body1)));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1+and+params.RESERVED%3Dfalse"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body2)));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain/parameters/JENKINS_SLAVE_REMOTEFS_ROOT"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body6)));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body7)));

        stubFor(get(urlEqualTo("/api/v2/status"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body8)));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=has+params.JENKINS_LABEL+"
                + "and+has+params.RESERVED+and+has+params.JENKINS_SLAVE_REMOTEFS_ROOT"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body9)));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain/parameters/JENKINS_LABEL"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body10)));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain/parameters/RESERVED"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body11)));
    }

    /**
     * Perform a test connection.
     * @throws ServletException if occurs.
     * @throws IOException if occurs.
     * @throws URISyntaxException if occurs.
     */
    @Test
    public void doTestConnection() throws ServletException, IOException, URISyntaxException {
        setupWireMock();
        DescriptorImpl descr = new ForemanSharedNodeCloud.DescriptorImpl();
        descr.doTestConnection(URL, USER, Secret.fromString(PASSWORD));
    }

    /**
     * Round trip test that configures, builds, provisions and tears down.
     * @throws IOException if occurs.
     * @throws URISyntaxException if occurs.
     * @throws InterruptedException if occurs.
     */
    @Test
    public void testRoundTrip() throws IOException, URISyntaxException, InterruptedException {

        setupWireMock();
        // Add cloud
        ForemanSharedNodeCloud fCloud = new ForemanSharedNodeCloud("mycloud", URL,
                USER, Secret.fromString(PASSWORD), "", 1);

        Computer[] computers = j.jenkins.getComputers();
        int initialComputerSet = computers.length;

        fCloud.setLauncherFactory(new ForemanDummyComputerLauncherFactory());
        j.getInstance().clouds.add(fCloud);
        fCloud.updateHostData();

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("label1"));

        assertTrue(job.scheduleBuild(0, new UserIdCause()));
        TestUtils.waitForBuilds(job, 1);
        try {
            //CS IGNORE MagicNumber FOR NEXT 2 LINES. REASON: Parent.
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting!");
        }

        Computer[] computersAfter = j.jenkins.getComputers();
        int finalComputerSet = computersAfter.length;

        assertTrue(initialComputerSet == finalComputerSet);
    }

    /**
     * Round trip test that simulates a loss of connection to
     * Foreman.
     * @throws IOException if occurs.
     * @throws URISyntaxException if occurs.
     * @throws InterruptedException if occurs.
     */
    @Test
    public void testWithLossOfConnection() throws Exception {
        final CountDownLatch disposeCheckLatch = new CountDownLatch(60);
        final CountDownLatch cleanedCheckLatch = new CountDownLatch(60);

        setupWireMock();
        // Add cloud
        ForemanSharedNodeCloud fCloud = new ForemanSharedNodeCloud("mycloud", URL,
                USER, Secret.fromString(PASSWORD), "", 1);

        fCloud.setLauncherFactory(new ForemanDummyComputerLauncherFactory());
        j.getInstance().clouds.add(fCloud);
        fCloud.updateHostData();

        AdministrativeMonitor adminMonitor = j.getInstance().getAdministrativeMonitor("AsyncResourceDisposer");
        assertTrue("adminMonitor not null for AsyncResourceDisposer", adminMonitor != null);
        assertTrue("adminMonitor is instanceof AsyncResourceDisposer", adminMonitor instanceof AsyncResourceDisposer);
        AsyncResourceDisposer disposer = (AsyncResourceDisposer)adminMonitor;

        final OneShotEvent finish = new OneShotEvent();
        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("label1"));

        assertTrue(job.scheduleBuild(0, new UserIdCause()));
        final Future<FreeStyleBuild> build = TestUtils.startBlockingAndFinishingBuild(job, finish);
        assertThat(job.getBuilds(), hasSize(1));

        // Let's simulate a Foreman connection error
        stubFor(get(urlMatching("/api/.*"))
                .willReturn(aResponse().withStatus(HTTPERROR)));
        finish.signal();
        build.get();

        assertThat(job.getBuilds(), hasSize(1));

        while(disposeCheckLatch.getCount() >= 0) {
            if (disposer.getBacklog().size() > 0) {
                boolean foundOurDisposalItem = false;
                for (AsyncResourceDisposer.WorkItem item: disposer.getBacklog()) {
                    Disposable disposableItem = item.getDisposable();
                    if (disposableItem instanceof DisposableImpl) {
                        foundOurDisposalItem = true;
                        break;
                    }
                }
                if (foundOurDisposalItem) {
                    break;
                }
            }
            Thread.sleep(1000);
            disposeCheckLatch.countDown();
        }

        if (disposeCheckLatch.getCount() <= 0) {
            throw new Exception("did not see DisposableImpl item in disposal backlog");
        }
        // Simulate Foreman is back online
        setupWireMock();
        Thread.sleep(1000);
        fCloud.updateHostData();
        while(cleanedCheckLatch.getCount() >= 0) {
            boolean foundOurDisposalItem = false;
            for (AsyncResourceDisposer.WorkItem item: disposer.getBacklog()) {
                Disposable disposableItem = item.getDisposable();
                if (disposableItem instanceof DisposableImpl) {
                    foundOurDisposalItem = true;
                }
            }
            if (!foundOurDisposalItem) {
                break;
            }
            Thread.sleep(1000);
            cleanedCheckLatch.countDown();
        }
        if (cleanedCheckLatch.getCount() <= 0) {
            throw new Exception("backlog of DisposableImpl items did not get cleaned up: " + cleanedCheckLatch.getCount());
        }

    }
}

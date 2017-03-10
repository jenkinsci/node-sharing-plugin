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
import static com.redhat.foreman.TestUtils.readFile;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import javax.servlet.ServletException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import hudson.model.FreeStyleBuild;
import hudson.util.OneShotEvent;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.junit.Rule;
import org.junit.Test;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.redhat.foreman.ForemanSharedNodeCloud.DescriptorImpl;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Cause.UserIdCause;
import hudson.model.labels.LabelAtom;
import hudson.util.Secret;

/**
 * Cloud Unit Tests.
 *
 */
public class ForemanSharedNodeCloudTest {

    private static final int HTTPOK = 200;
    private static final int HTTPERROR = 500;

    private static final int SERVICE_PORT = new Random().nextInt(100) + 32000;

    private static final String URL = "http://localhost:" + SERVICE_PORT + "/api";
    private static final String USER = "admin";
    private static final String PASSWORD = "changeme";
    private static final String SUT_HOSTNAME = "localhost.localdomain";

    /**
     * Rule for Jenkins.
     */
    @Rule
    public final ForemanTestRule j = new ForemanTestRule();

    /**
     * Rule for wiremock.
     */
    @Rule
    public final WireMockRule wireMockRule = new WireMockRule(SERVICE_PORT);

    /**
     * Prepare wiremocks.
     */
    // TODO: Avoid shared fixture
    private void setupWireMock() throws IOException, URISyntaxException {
        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1"))
                .willReturn(ok("body1.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1+and+params.RESERVED%3D"
                + "false+and+has+params.JENKINS_SLAVE_REMOTEFS_ROOT"))
                .willReturn(ok("body2.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlocalhost.localdomain"))
                .willReturn(ok("body4.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts"))
                .willReturn(ok("body1.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1+and+params.RESERVED%3Dfalse"))
                .willReturn(ok("body2.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain/parameters/JENKINS_SLAVE_REMOTEFS_ROOT"))
                .willReturn(ok("body6.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=has+params.JENKINS_LABEL+"
                + "and+has+params.RESERVED+and+has+params.JENKINS_SLAVE_REMOTEFS_ROOT"))
                .willReturn(ok("body9.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain/parameters/JENKINS_LABEL"))
                .willReturn(ok("body10.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain/parameters/RESERVED"))
                .willReturn(ok("body11.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.RESERVED%3Dfalse"))
                .willReturn(ok("body2.txt")));

    }

    /**
     * Test for configuration of a Foreman Cloud.
     *
     * @throws Exception if occurs.
     */
    @Test
    public void testConfigRoundtrip() throws Exception {
        ForemanSharedNodeCloud orig = j.addForemanCloud("mycloud", URL, USER, PASSWORD);

        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        j.assertEqualBeans(orig, j.jenkins.clouds.iterator().next(),
                "cloudName,url,user,password,credentialsId");
    }

    /**
     * Perform a test connection.
     *
     * @throws ServletException if occurs.
     * @throws IOException if occurs.
     * @throws URISyntaxException if occurs.
     */
    @Test
    public void doTestConnection() throws Exception {
        stubServiceStatus();
        DescriptorImpl descr = new ForemanSharedNodeCloud.DescriptorImpl();
        assertThat(
                descr.doTestConnection(URL, USER, Secret.fromString(PASSWORD)).getMessage(),
                containsString("1.5.3")
        );
    }

    /**
     * Round trip test that configures, builds, provisions and tears down.
     *
     * @throws Exception if occurs.
     */
    @Test
    public void testRoundTrip() throws Exception {
        Computer[] computers = j.jenkins.getComputers();
        int initialComputerSet = computers.length;

        j.addForemanCloud("mycloud", URL, USER, PASSWORD);

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("label1"));

        assertTrue(job.scheduleBuild(0, new UserIdCause()));
        TestUtils.waitForBuilds(job, 1);

        j.triggerCleanupThread();
        j.waitForEmptyAsyncResourceDisposer();

        Computer[] computersAfter = j.jenkins.getComputers();
        int finalComputerSet = computersAfter.length;
        assertTrue(initialComputerSet == finalComputerSet);
    }

    /**
     * Round trip test that simulates a loss of connection to
     * Foreman.
     *
     * @throws IOException if occurs.
     * @throws URISyntaxException if occurs.
     * @throws InterruptedException if occurs.
     */
    @Test
    public void testWithLossOfConnection() throws Exception {
        setupWireMock();
        j.addForemanCloud("mycloud", URL, USER, PASSWORD);

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("label1"));

        final OneShotEvent finish = new OneShotEvent();
        final Future<FreeStyleBuild> build = j.startBlockingAndFinishingBuild(job, finish);
        assertThat(job.getBuilds(), hasSize(1));

        // Let's simulate a Foreman connection error
        stubFor(get(urlMatching("/api/.*"))
                .willReturn(aResponse()
                        .withStatus(HTTPERROR)));
        finish.signal();
        build.get();

        assertThat(job.isBuilding(), equalTo(false));
        assertThat(job.getBuilds(), hasSize(1));

        // Wait for creation of disposable item
        final CountDownLatch disposeCheckLatch = new CountDownLatch(30);
        while(disposeCheckLatch.getCount() >= 0) {
            if (j.getAsyncResourceDisposer().getBacklog().size() > 0) {
                boolean foundOurDisposalItem = false;
                for (AsyncResourceDisposer.WorkItem item: j.getAsyncResourceDisposer().getBacklog()) {
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
            j.triggerCleanupThread();
            Thread.sleep(1000);
            disposeCheckLatch.countDown();
        }

        if (disposeCheckLatch.getCount() <= 0) {
            throw new Exception("did not see DisposableImpl item in disposal backlog");
        }
        // Simulate Foreman is back online
        setupWireMock();
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain/parameters/RESERVED"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(readFile("body11.txt").replace("false", "Reserved for "+j.getInstance().getRootUrl()))));

        // Wait for cleanup procedure
        j.waitForEmptyAsyncResourceDisposer();
    }

    @Test
    public void getHostData() throws Exception {
        ForemanSharedNodeCloud orig = j.addForemanCloud("mycloud", URL, USER, PASSWORD);
        ForemanAPI api = orig.getForemanAPI();

        stubHostInfoIdle();

        HostInfo hi = api.getHostInfo("localhost.localdomain");
        assertEquals("localhost.localdomain", hi.getName());
        assertEquals("label1", hi.getLabels());
        assertEquals("/tmp/remoteFSRoot", hi.getRemoteFs());
        assertEquals(null, hi.getReservedFor());
        assertFalse(hi.isReserved());

        String reserveReason = stubHostInfoReservedForMe();

        hi = api.getHostInfo("localhost.localdomain");
        assertEquals("localhost.localdomain", hi.getName());
        assertEquals("label1", hi.getLabels());
        assertEquals("/tmp/remoteFSRoot", hi.getRemoteFs());
        assertEquals(reserveReason, hi.getReservedFor());
        assertTrue(hi.isReserved());

        assertEquals(null, api.getHostInfo("no_such_host"));

        stubFor(get(urlEqualTo("/api/v2/hosts/broken.host")).willReturn(aResponse().withStatus(500).withBody("Boom!")));
        try {
            api.getHostInfo("broken.host");
            fail();
        } catch (ForemanAPI.CommunicationError ex) {
            assertThat(ex.getMessage(), containsString("500"));
        }
    }

    @Test
    public void getVersion() throws Exception {
        ForemanSharedNodeCloud orig = j.addForemanCloud("mycloud", URL, USER, PASSWORD);
        ForemanAPI api = orig.getForemanAPI();

        try {
            api.getVersion();
            fail();
        } catch (ForemanAPI.CommunicationError ex) {
            // Expected
        }

        stubServiceStatus();

        assertEquals("1.5.3", api.getVersion());
    }

    @Test
    public void reserveHost() throws Exception {
        HostInfo freeHost = new ObjectMapper().readerFor(HostInfo.class).readValue(readFile("host-info-idle.txt"));

        ForemanSharedNodeCloud orig = j.addForemanCloud("mycloud", URL, USER, PASSWORD);
        ForemanAPI api = orig.getForemanAPI();

        assertEquals(null, api.reserveHost(freeHost));

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("reserveHost")
                .willReturn(ok("host-info-idle.txt")));
        stubFor(get(urlMatching("/api/hosts_reserve.+"))
                .inScenario("reserveHost")
                .willSetStateTo("reserved")
                .willReturn(ok("host-reserved-success.txt")));
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("reserveHost")
                .whenScenarioStateIs("reserved")
                .willReturn(ok("host-info-reserved-for-me.txt", ForemanAPI.getReserveReason())));

        HostInfo reservedHost = api.reserveHost(freeHost);
        assertEquals(ForemanAPI.getReserveReason(), reservedHost.getReservedFor());
        assertEquals("localhost.localdomain", reservedHost.getName());
    }

    @Test
    public void releaseHost() throws Exception {
        String reserveReason = ForemanAPI.getReserveReason();

        ForemanSharedNodeCloud orig = j.addForemanCloud("mycloud", URL, USER, PASSWORD);
        ForemanAPI api = orig.getForemanAPI();

        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("releaseHost")
                .willReturn(ok("host-info-reserved-for-me.txt", reserveReason)));
        stubFor(get(urlEqualTo("/api/hosts_release?query=name+~+localhost.localdomain"))
                .inScenario("releaseHost")
                .willSetStateTo("released")
                .willReturn(ok("host-release-success.txt")));
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("releaseHost")
                .whenScenarioStateIs("released")
                .willReturn(ok("host-info-idle.txt")));

        assertEquals(reserveReason, api.getHostInfo(SUT_HOSTNAME).getReservedFor());
        api.release(SUT_HOSTNAME);
        assertEquals(null, api.getHostInfo(SUT_HOSTNAME).getReservedFor());

        // Call should be idempotent
        api.release(SUT_HOSTNAME);
        assertEquals(null, api.getHostInfo(SUT_HOSTNAME).getReservedFor());

        // And deal with host removal
        api.release("so_such_host");
        assertEquals(null, api.getHostInfo(SUT_HOSTNAME).getReservedFor());
    }

    private void stubServiceStatus() {
        stubFor(get(urlEqualTo("/api/v2/status")).willReturn(ok("service-status.txt")));
    }

    private String stubHostInfoReservedForMe() throws IOException, URISyntaxException {
        String reserveReason = ForemanAPI.getReserveReason();
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain")).willReturn(ok("host-info-reserved-for-me.txt", reserveReason)));
        return reserveReason;
    }

    private void stubHostInfoIdle() throws IOException, URISyntaxException {
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain")).willReturn(ok("host-info-idle.txt")));
    }

    private ResponseDefinitionBuilder ok(String path, String... args) {
        String body = String.format(readFile(path), args);
        return aResponse()
                .withStatus(HTTPOK)
                .withHeader("Content-Type", "text/json")
                .withBody(body);
    }
}

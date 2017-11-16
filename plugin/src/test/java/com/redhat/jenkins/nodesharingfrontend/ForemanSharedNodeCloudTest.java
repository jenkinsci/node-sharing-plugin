package com.redhat.jenkins.nodesharingfrontend;
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
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterableOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.servlet.ServletException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.AbstractCloudSlave;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import com.github.tomakehurst.wiremock.junit.WireMockRule;

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

    private static final int SERVICE_PORT = new Random().nextInt(100) + 32000;

    private static final String URL = "http://localhost:" + SERVICE_PORT + "/api";
    private static final String USER = "admin";
    private static final String PASSWORD = "changeme";
    private static final String SUT_HOSTNAME = "localhost.localdomain";

    @Rule public final ForemanTestRule j = new ForemanTestRule();
    @Rule public final WireMockRule wireMockRule = new WireMockRule(SERVICE_PORT);

    private String hostInfoResponseFile = "host-info.txt";

    // Use per-test instance rather than the static helper methods as they are talking to thread local instance so we
    // can not configure stubs from different threads.
    private WireMock wireMock;
    private StubMapping stubFor(MappingBuilder mapping) {
        if (wireMock == null) {
            wireMock = new WireMock(SERVICE_PORT);
        }
        StubMapping build = mapping.build();
        wireMock.register(build);
        return build;
    }

    /**
     * Prepare wiremocks.
     */
    // TODO: Avoid shared fixture
    private void setupWireMock() throws IOException, URISyntaxException {
        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1"))
                .willReturn(ok("body1.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1+and+params.RESERVED%3Dfalse+and+has+params.JENKINS_SLAVE_REMOTEFS_ROOT"))
                .willReturn(ok("body2.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlocalhost.localdomain"))
                .willReturn(ok("body4.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts"))
                .willReturn(ok("body1.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1+and+params.RESERVED%3Dfalse"))
                .willReturn(ok("body2.txt")));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.RESERVED%3Dfalse"))
                .willReturn(ok("body2.txt")));

    }

    /**
     * Test for configuration of a Foreman Cloud.
     *
     * @throws Exception if occurs.
     */
    @Ignore
    @Test
    public void testConfigRoundtrip() throws Exception {
        SharedNodeCloud orig = j.addForemanCloud("mycloud", URL);

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
//    @Ignore
//    @Test
//    public void doTestConnection() throws Exception {
//        stubServiceStatus();
//        SharedNodeCloud.DescriptorImpl descr = new SharedNodeCloud.DescriptorImpl();
//        assertThat(descr.doTestConnection(URL), containsString("1.5.3"));
//    }

    /**
     * Round trip test that configures, builds, provisions and tears down.
     */
    @Ignore
    @Test
    public void testRoundTrip() throws Exception {
//        String reserveReason = ForemanAPI.getReserveReason();
//        stubSearchInventory();
//        stubReserveScenario();
//
//        Computer[] computers = j.jenkins.getComputers();
//        int initialComputerSet = computers.length;
//
//        SharedNodeCloud cloud = j.addForemanCloud("mycloud", URL);
//
//        FreeStyleProject job = j.createFreeStyleProject();
//        job.setAssignedLabel(new LabelAtom("label1"));
//
//        job.scheduleBuild2(0, new UserIdCause()).waitForStart();
//
//        ForemanAPI foremanAPI = cloud.getForemanAPI();
//        assertEquals(reserveReason, foremanAPI.getHostInfo(SUT_HOSTNAME).getReservedFor());
//        stubReleaseScenario(reserveReason);
//
//        TestUtils.waitForBuilds(job, 1);
//
//        j.waitForEmptyAsyncResourceDisposer();
//
//        Computer[] computersAfter = j.jenkins.getComputers();
//        int finalComputerSet = computersAfter.length;
//        assertEquals(initialComputerSet, finalComputerSet);
//
//        assertEquals(null, foremanAPI.getHostInfo(SUT_HOSTNAME).getReservedFor());
    }

    @Ignore
    @Test
    public void foremanBreakInTheMiddleOfTheBuildAndDisposerReleasesTheInstance() throws Exception {
        String reserveReason = ForemanAPI.getReserveReason();
        stubSearchInventory();
        stubReserveScenario();

        Computer[] computers = j.jenkins.getComputers();
        int initialComputerSet = computers.length;

        SharedNodeCloud cloud = j.addForemanCloud("mycloud", URL);

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("label1"));
        job.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                // Simulate a Foreman connection error while disposing
                stubFor(get(urlMatching("/api/.*")).willReturn(aResponse().withStatus(HTTPERROR)));
                return true;
            }
        });

        FreeStyleBuild build = job.scheduleBuild2(0, new UserIdCause()).get();
        Node node = build.getBuiltOn();
        assertEquals(SUT_HOSTNAME, node.getNodeName());

        j.waitUntilNoActivity(); // Despite the build being completed, I have observed node not yet idle for removal
        AsyncResourceDisposer.get().reschedule();

        List<AsyncResourceDisposer.WorkItem> disposables = getDisposables();
        assertThat(disposables, not(emptyIterableOf(AsyncResourceDisposer.WorkItem.class)));
        for (AsyncResourceDisposer.WorkItem disposable : disposables) {
            Disposable.State state;
            do {
                Thread.sleep(100);
                state = disposable.getLastState();
            } while (state instanceof Disposable.State.ToDispose);

            assertThat(state, instanceOf(Disposable.State.Thrown.class));
        }

        wireMock.resetMappings(); // Remove broken mapping
        stubReleaseScenario(reserveReason);

        AsyncResourceDisposer.get().reschedule();
        Thread.sleep(100);
        j.waitForEmptyAsyncResourceDisposer();

        assertThat(getDisposables(), emptyIterableOf(AsyncResourceDisposer.WorkItem.class));

        Computer[] computersAfter = j.jenkins.getComputers();
        int finalComputerSet = computersAfter.length;
        assertTrue(initialComputerSet == finalComputerSet);

//        assertEquals(null, cloud.getForemanAPI().getHostInfo(SUT_HOSTNAME).getReservedFor());
    }

    @Ignore
    @Test
    public void customJavaPath() throws Exception {
        hostInfoResponseFile = "host-info-custom-java-path.txt";
        stubSearchInventory();
        stubReserveScenario();

        SharedNodeCloud cloud = j.addForemanCloud("mycloud", URL);
        // Do not use the test specific one so we actually test the value propagation to SSHLauncher
        cloud.setLauncherFactory(null);
        AbstractCloudSlave node = (AbstractCloudSlave) cloud.provision(Label.get("label1"), 1).iterator().next().future.get();
        SSHLauncher launcher = (SSHLauncher) node.createComputer().getLauncher();
        assertEquals("/custom/java/path", launcher.getJavaPath());
    }

    private List<AsyncResourceDisposer.WorkItem> getDisposables() {
        List<AsyncResourceDisposer.WorkItem> out = new ArrayList<AsyncResourceDisposer.WorkItem>();
        for (AsyncResourceDisposer.WorkItem item : AsyncResourceDisposer.get().getBacklog()) {
            if (item.getDisposable() instanceof DisposableImpl) {
                out.add(item);
            }
        }
        return out;
    }

    @Ignore
    @Test
    public void getHostData() throws Exception {
//        SharedNodeCloud orig = j.addForemanCloud("mycloud", URL);
//        ForemanAPI api = orig.getForemanAPI();
//
//        stubHostInfoIdle();
//
//        HostInfo hi = api.getHostInfo(SUT_HOSTNAME);
//        assertEquals(SUT_HOSTNAME, hi.getName());
//        assertEquals("label1", hi.getLabels());
//        assertEquals("/tmp/remoteFSRoot", hi.getRemoteFs());
//        assertEquals(null, hi.getReservedFor());
//        assertFalse(hi.isReserved());
//
//        String reserveReason = stubHostInfoReservedForMe();
//
//        hi = api.getHostInfo(SUT_HOSTNAME);
//        assertEquals(SUT_HOSTNAME, hi.getName());
//        assertEquals("label1", hi.getLabels());
//        assertEquals("/tmp/remoteFSRoot", hi.getRemoteFs());
//        assertEquals(reserveReason, hi.getReservedFor());
//        assertTrue(hi.isReserved());
//
//        assertEquals(null, api.getHostInfo("no_such_host"));
//
//        stubFor(get(urlEqualTo("/api/v2/hosts/broken.host")).willReturn(aResponse().withStatus(500).withBody("Boom!")));
//        try {
//            api.getHostInfo("broken.host");
//            fail();
//        } catch (ForemanAPI.CommunicationError ex) {
//            assertThat(ex.getMessage(), containsString("500"));
//        }
    }

    @Ignore
    @Test
    public void getVersion() throws Exception {
        SharedNodeCloud orig = j.addForemanCloud("mycloud", URL);
        Api api = orig.getApi();

        try {
            api.doDiscover();
            fail();
        } catch (ForemanAPI.CommunicationError ex) {
            // Expected
        }

        stubServiceStatus();

        assertEquals("1.5.3", api.doDiscover());
    }

    @Ignore
    @Test
    public void reserveHost() throws Exception {
//        HostInfo freeHost = new ObjectMapper().readerFor(HostInfo.class).readValue(String.format(TestUtils.readFile(hostInfoResponseFile), "false"));
//
//        SharedNodeCloud orig = j.addForemanCloud("mycloud", URL);
//        ForemanAPI api = orig.getForemanAPI();
//
//        assertEquals(null, api.reserveHost(freeHost));
//
//        stubReserveScenario();
//
//        HostInfo reservedHost = api.reserveHost(freeHost);
//        assertEquals(ForemanAPI.getReserveReason(), reservedHost.getReservedFor());
//        assertEquals(SUT_HOSTNAME, reservedHost.getName());
    }

    @Ignore
    @Test
    public void releaseHost() throws Exception {
        String reserveReason = ForemanAPI.getReserveReason();

        SharedNodeCloud orig = j.addForemanCloud("mycloud", URL);
        Api api = orig.getApi();

        stubReleaseScenario(reserveReason);

//        assertEquals(reserveReason, api.getHostInfo(SUT_HOSTNAME).getReservedFor());
        api.doRelease(SUT_HOSTNAME);
//        assertEquals(null, api.getHostInfo(SUT_HOSTNAME).getReservedFor());

        // Call should be idempotent
        api.doRelease(SUT_HOSTNAME);
//        assertEquals(null, api.getHostInfo(SUT_HOSTNAME).getReservedFor());

        // And deal with host removal
        api.doRelease("so_such_host");
//        assertEquals(null, api.getHostInfo(SUT_HOSTNAME).getReservedFor());
    }

    private void stubReserveScenario() {
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("reserveHost")
                .willReturn(ok(hostInfoResponseFile, "false")));
        stubFor(get(urlMatching("/api/hosts_reserve.+"))
                .inScenario("reserveHost")
                .willSetStateTo("reserved")
                .willReturn(ok("host-reserved-success.txt")));
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("reserveHost")
                .whenScenarioStateIs("reserved")
                .willReturn(ok(hostInfoResponseFile, ForemanAPI.getReserveReason())));
    }

    private void stubReleaseScenario(String reserveReason) {
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("releaseHost")
                .willReturn(ok(hostInfoResponseFile, reserveReason)));
        stubFor(get(urlEqualTo("/api/hosts_release?query=name+~+localhost.localdomain"))
                .inScenario("releaseHost")
                .willSetStateTo("released")
                .willReturn(ok("host-release-success.txt")));
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain"))
                .inScenario("releaseHost")
                .whenScenarioStateIs("released")
                .willReturn(ok(hostInfoResponseFile, "false")));
    }

    private void stubServiceStatus() {
        stubFor(get(urlEqualTo("/api/v2/status")).willReturn(ok("service-status.txt")));
    }

    private String stubHostInfoReservedForMe() throws IOException, URISyntaxException {
        String reserveReason = ForemanAPI.getReserveReason();
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain")).willReturn(ok(hostInfoResponseFile, reserveReason)));
        return reserveReason;
    }

    private void stubHostInfoIdle() throws IOException, URISyntaxException {
        stubFor(get(urlEqualTo("/api/v2/hosts/localhost.localdomain")).willReturn(ok(hostInfoResponseFile, "false")));
    }

    private void stubSearchInventory() {
        String url = "/api/v2/hosts?search=has+params.JENKINS_LABEL+and+has+params.RESERVED+and+has+params.JENKINS_SLAVE_REMOTEFS_ROOT";
        stubFor(get(urlEqualTo(url)).willReturn(ok("hosts-search-reservable.txt")));
    }

    private ResponseDefinitionBuilder ok(String path, String... args) {
        String body = String.format(TestUtils.readFile(path), args);
        return aResponse()
                .withStatus(HTTPOK)
                .withHeader("Content-Type", "text/json")
                .withBody(body);
    }
}

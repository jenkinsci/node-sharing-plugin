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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.servlet.ServletException;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.redhat.foreman.ForemanCloud.DescriptorImpl;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Cause.UserIdCause;
import hudson.model.labels.LabelAtom;
import hudson.util.Secret;

public class ForemanCloudTest {

    private static final int HTTPOK = 200;

    private static final String URL = "http://localhost:32789/api";
    private static final String USER = "admin";
    private static final String PASSWORD = "changeme";

    @Rule 
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public final WireMockRule wireMockRule = new WireMockRule(32789);

    @Test
    public void testConfigRoundtrip() throws Exception {
        ForemanCloud orig = new ForemanCloud("mycloud", URL, 
                USER, Secret.fromString(PASSWORD));
        j.getInstance().clouds.add(orig);
        j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

        j.assertEqualBeans(orig, j.jenkins.clouds.iterator().next(), "cloudName,url,user,password");
    }

    private static String readFile(String path, Charset encoding) 
            throws IOException 
    {
        byte[] encoded;
        try {
            encoded = Files.readAllBytes(Paths.get(ForemanCloudTest.class.getResource(path).toURI()));
            return new String(encoded, encoding);
        } catch (URISyntaxException e) {
        }
        return null;
    }

    private void setupWireMock() throws IOException {
        String body1 = readFile("body1.txt", StandardCharsets.UTF_8);
        String body2 = readFile("body2.txt", StandardCharsets.UTF_8);
        String body3 = readFile("body3.txt", StandardCharsets.UTF_8);
        String body4 = readFile("body4.txt", StandardCharsets.UTF_8);
        String body5 = readFile("body5.txt", StandardCharsets.UTF_8);
        String body6 = readFile("body6.txt", StandardCharsets.UTF_8);
        String body7 = readFile("body7.txt", StandardCharsets.UTF_8);

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1"))
                .willReturn(aResponse()
                        .withStatus(HTTPOK)
                        .withHeader("Content-Type", "text/json")
                        .withBody(body1)));

        stubFor(get(urlEqualTo("/api/v2/hosts?search=params.JENKINS_LABEL%3Dlabel1+and+params.RESERVED%3D" +
                "false+and+has+params.JENKINS_SLAVE_REMOTEFS_ROOT"))
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

    }

    @Test
    public void doTestConnection() throws ServletException, IOException {
        setupWireMock();
        DescriptorImpl descr = new ForemanCloud.DescriptorImpl();
        descr.doTestConnection(URL, USER, Secret.fromString(PASSWORD));
    }

    @Test
    public void testRoundTrip() throws IOException, URISyntaxException, InterruptedException {

        setupWireMock();
        // Add cloud
        ForemanCloud fCloud = new ForemanCloud("mycloud", URL, 
                USER, Secret.fromString(PASSWORD));

        fCloud.setLauncherFactory(new ForemanDummyComputerLauncherFactory());
        j.getInstance().clouds.add(fCloud);

        FreeStyleProject job = j.createFreeStyleProject();
        job.setAssignedLabel(new LabelAtom("label1"));

        assertTrue(job.scheduleBuild(0, new UserIdCause()));
        TestUtils.waitForBuilds(job, 1);

        Computer[] computers = j.jenkins.getComputers();
        int initialComputerSet = computers.length;
        for (int i = 0; i < initialComputerSet ; i++) {
            if (computers[i] instanceof ForemanComputer) {
                ((ForemanComputer)computers[i]).getNode().terminate();
                break;
            }
        }

        Computer[] computersAfter = j.jenkins.getComputers();
        int finalComputerSet = computersAfter.length;
        assertTrue(initialComputerSet > finalComputerSet);
    }

}

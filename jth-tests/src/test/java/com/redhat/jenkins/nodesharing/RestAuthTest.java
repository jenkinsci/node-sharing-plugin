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

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Verify all REST endpoints are secured.
 */
public class RestAuthTest {

    private static final String UNPRIVILIGED_USER = "regularUser";

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Before
    public void setUp() {
        j.getMockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to(UNPRIVILIGED_USER);
    }

    @Test
    public void backendEndpoints() throws Exception {
        j.singleJvmGrid(j.jenkins);

        for (Method method : com.redhat.jenkins.nodesharingbackend.Api.class.getDeclaredMethods()) {
            if (method.getName().startsWith("do") && Modifier.isPublic(method.getModifiers())) {
                URL url = new URL(Pool.getInstance().getConfig().getOrchestratorUrl() + "node-sharing-orchestrator/" + actionMethodUrl(method));
                verifyCall(method, url);
            }
        }
    }

    @Test
    public void frontendEndpoints() throws Exception {
        GitClient gitClient = j.singleJvmGrid(j.jenkins);
        SharedNodeCloud cloud = j.addSharedNodeCloud(gitClient.getWorkTree().getRemote());

        ExecutorJenkins executor = cloud.getLatestConfig().getJenkinses().iterator().next();
        RestEndpoint rest = executor.getRest(gitClient.getWorkTree().getRemote(), j.getRestCredential());

        for (Method method : com.redhat.jenkins.nodesharingfrontend.Api.class.getDeclaredMethods()) {
            if (method.getName().startsWith("do") && Modifier.isPublic(method.getModifiers())) {
                URL url = rest.post(actionMethodUrl(method)).getURI().toURL();
                verifyCall(method, url);
            }
        }
    }

    private String actionMethodUrl(Method method) {
        String name = method.getName();
        return name.substring(2, 3).toLowerCase() + name.substring(3);
    }

    private void verifyCall(Method method, URL url) throws Exception {
        assertNotNull(method.getName() + " should be annotated with @RequirePOST", method.getAnnotation(RequirePOST.class));

        JenkinsRule.WebClient wc = webClient();
        WebRequest request = wc.addCrumb(new WebRequest(url, HttpMethod.POST));
        WebResponse webResponse = wc.login(UNPRIVILIGED_USER, UNPRIVILIGED_USER).getPage(request).getWebResponse();
        assertEquals(url.toExternalForm(), 403, webResponse.getStatusCode());
        assertThat(webResponse.getContentAsString(), containsString(UNPRIVILIGED_USER + " is missing the NodeSharing/Reserve permission"));
    }

    private JenkinsRule.WebClient webClient() {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return wc;
    }
}

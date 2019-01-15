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

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.Build;
import com.offbytwo.jenkins.model.Job;
import com.offbytwo.jenkins.model.JobWithDetails;
import com.redhat.jenkins.nodesharing.utils.ExternalFixture;
import com.redhat.jenkins.nodesharing.utils.ExternalJenkinsRule;
import com.redhat.jenkins.nodesharing.utils.GridRule;
import com.redhat.jenkins.nodesharing.utils.GridRule.Orchestrator;
import com.redhat.jenkins.nodesharing.utils.GridRule.Executor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class JcascTest {

    public @Rule TemporaryFolder tmp = new TemporaryFolder();
    public @Rule GridRule jcr = new GridRule(tmp);

    @Test
    @ExternalFixture(name = "o",  roles = Orchestrator.class, resource = "orchestrator.yaml",   injectPlugins = {"matrix-auth"})
    @ExternalFixture(name = "e0", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = {"matrix-auth", "matrix-project"})
    @ExternalFixture(name = "e1", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = {"matrix-auth", "matrix-project"})
    @ExternalFixture(name = "e2", roles = Executor.class,     resource = "executor-smoke.yaml", injectPlugins = {"matrix-auth", "matrix-project"})
    public void smoke() throws Exception {
        ExternalJenkinsRule.Fixture o = jcr.fixture("o");
        ExternalJenkinsRule.Fixture e0 = jcr.fixture("e0");
        ExternalJenkinsRule.Fixture e1 = jcr.fixture("e1");
        ExternalJenkinsRule.Fixture e2 = jcr.fixture("e2");

        for (ExternalJenkinsRule.Fixture fixture : Arrays.asList(e0, e1, e2)) {
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(10000);
                    System.out.println('.');
                    verifyBuildHasRun(fixture, "sol", "win");
                } catch (AssertionError ex) {
                    if (i == 4) throw ex;
                    // Retry
                }
            }
        }
    }

    private void verifyBuildHasRun(ExternalJenkinsRule.Fixture executor, String... jobNames) throws IOException {
        JenkinsServer jenkinsServer = executor.getClient();
        Map<String, Job> jobs = jenkinsServer.getJobs();
        for (String jobName : jobNames) {
            JobWithDetails job = jobs.get(jobName).details();
            assertThat(job.getNextBuildNumber(), greaterThanOrEqualTo(2));
            Build solBuild = job.getLastFailedBuild();
            if (solBuild != Build.BUILD_HAS_NEVER_RUN) {
                fail("All builds of " + jobName + " succeeded on " + executor.getUri() + ":\n" + solBuild.details().getConsoleOutputText());
            }
        }
    }
}

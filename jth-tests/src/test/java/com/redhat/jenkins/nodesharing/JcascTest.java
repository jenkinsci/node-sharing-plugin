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

import com.redhat.jenkins.nodesharing.utils.ExternalFixture;
import com.redhat.jenkins.nodesharing.utils.ExternalJenkinsRule;
import com.redhat.jenkins.nodesharing.utils.GridRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JcascTest {

    public static final String ORCHESTRATOR = "../backend-plugin/target/node-sharing-orchestrator.hpi";
    public static final String EXECUTOR = "../plugin/target/node-sharing-executor.hpi";

    public @Rule TemporaryFolder tmp = new TemporaryFolder();
    public @Rule GridRule jcr = new GridRule(tmp);

    @Test
    @ExternalFixture(name = "orchestrator", resource = "orchestrator.yaml", injectPlugins = {"matrix-auth", ORCHESTRATOR})
    @ExternalFixture(name = "executor0",    resource = "executor.yaml",     injectPlugins = {"matrix-auth", "matrix-project", "job-dsl", EXECUTOR})
    @ExternalFixture(name = "executor1",    resource = "executor.yaml",     injectPlugins = {"matrix-auth", "matrix-project", "job-dsl", "../plugin/target/node-sharing-executor.hpi"})
    @ExternalFixture(name = "executor2",    resource = "executor.yaml",     injectPlugins = {"matrix-auth", "matrix-project", "job-dsl", "../plugin/target/node-sharing-executor.hpi"})
    public void delegateBuildsToMultipleExecutors() throws Exception {
        ExternalJenkinsRule.Fixture o = jcr.fixture("orchestrator");
        ExternalJenkinsRule.Fixture e1 = jcr.fixture("executor1");
        ExternalJenkinsRule.Fixture e2 = jcr.fixture("executor1");
        ExternalJenkinsRule.Fixture e3 = jcr.fixture("executor1");

        System.out.println(o.getLog().readToString());
        //System.out.println(e.getLog().readToString());
        jcr.interactiveBreak();
    }
}

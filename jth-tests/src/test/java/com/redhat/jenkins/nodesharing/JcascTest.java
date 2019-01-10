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
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JcascTest {

    public @Rule TemporaryFolder tmp = new TemporaryFolder();
    public @Rule GridRule jcr = new GridRule(tmp);
    private GitClient configRepo;

    @Before
    public void before() throws Exception {
        configRepo = NodeSharingJenkinsRule.createConfigRepo();
    }

    @After
    public void after() throws Exception {
        configRepo.getWorkTree().deleteRecursive();
    }

    @Test
    @ExternalFixture(name = "orchestrator", resource = "orchestrator.yaml", injectPlugins = "../backend-plugin/target/node-sharing-orchestrator.hpi")
    @ExternalFixture(name = "executor",     resource = "executor.yaml",     injectPlugins = "../plugin/target/node-sharing-executor.hpi")
    public void test() throws Exception {
        ExternalJenkinsRule.Fixture orchestrator = jcr.fixture("orchestrator");
        ExternalJenkinsRule.Fixture executor = jcr.fixture("executor");
        System.out.println(orchestrator.getUrl());
        System.out.println(executor.getUrl());
        jcr.interactiveBreak();
    }
}

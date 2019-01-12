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
package com.redhat.jenkins.nodesharing.utils;

import hudson.EnvVars;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Decorate ExternalJenkinsRule with node sharing specific extensions.
 */
public class GridRule extends ExternalJenkinsRule {

    private GitClient configRepo;

    public GridRule(TemporaryFolder tmp) {
        super(tmp);
    }

    public GitClient configRepo() {
        return configRepo;
    }

    @Override
    public Statement apply(Statement base, Description d) {
        // It is needed to intercept the rule chain both from it inside and outside. ConfigRepo needs to be setup before
        // fixture runs but it can be populate only once it is already running. Structure looks like this:
        // outer ( fixtureRule ( inner ( base() ) ) )
        Statement inner = new Statement() {
            @Override public void evaluate() throws Throwable {
                try {
                    TestUtils.declareExecutors(configRepo, Collections.emptyMap()); // Erase executors from dummy fixture
                    // Add provisioned fixtures to config repo
                    for (Fixture fixture : getFixtures().values()) {
                        String name = fixture.getAnnotation().name();
                        if (name.startsWith("orchestrator")) {
                            TestUtils.declareOrchestrator(configRepo, fixture.getUri().toString());
                        } else if (name.startsWith("executor")) {
                            TestUtils.declareExecutor(configRepo, name, fixture.getUri().toString());
                        } else {
                            throw new IllegalArgumentException("Fixture name is expected to start with 'orchestrator' or 'executor'. Got " + name);
                        }
                    }
                    base.evaluate();
                } finally {

                }
            }
        };
        Statement fixtureRule = super.apply(inner, d);
        Statement outer = new Statement() {
            @Override public void evaluate() throws Throwable {
                try {
                    configRepo = TestUtils.createConfigRepo();
                    fixtureRule.evaluate();
                } finally {
                    configRepo.getWorkTree().deleteRecursive();
                }
            }
        };
        return outer;
    }

    @Override
    protected List<String> startWithJvmOptions(List<String> defaults, ExternalFixture fixture) {
        if (fixture.name().startsWith("orchestrator")) {
            defaults.add("-Dcom.redhat.jenkins.nodesharingbackend.Pool.ENDPOINT=" + configRepo.getWorkTree().getRemote());
            defaults.add("-Dcom.redhat.jenExecukins.nodesharingbackend.Pool.USERNAME=jerry");
            defaults.add("-Dcom.redhat.jenkins.nodesharingbackend.Pool.PASSWORD=jerry");
        }
        return defaults;
    }

    @Override
    protected EnvVars startWithEnvVars(EnvVars defaults, ExternalFixture fixture) {
        if (fixture.name().startsWith("executor")) {
            defaults.put("JCASC_CONFIG_REPO_URL", configRepo.getWorkTree().getRemote());
        }
        return defaults;
    }
}

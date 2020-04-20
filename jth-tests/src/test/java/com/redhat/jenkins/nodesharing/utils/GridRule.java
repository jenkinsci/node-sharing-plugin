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
import org.junit.Assert;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.redhat.jenkins.nodesharing.utils.TestUtils.makeNodesLaunchable;

/**
 * Decorate ExternalJenkinsRule with node sharing specific extensions.
 */
public class GridRule extends ExternalJenkinsRule {

    public static final String ORCHESTRATOR = "../backend-plugin/target/node-sharing-orchestrator.hpi";
    public static final String EXECUTOR = "../plugin/target/node-sharing-executor.hpi";
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
        // fixture runs but it can be populated only once fixtures are running. Structure looks like this:
        // outer ( ExternalJenkinsRule ( inner ( base() ) ) )
        Statement inner = new Statement() {
            @Override public void evaluate() throws Throwable {
                TestUtils.declareExecutors(configRepo, Collections.emptyMap()); // Erase executors from dummy fixture
                // Add provisioned fixtures to config repo
                for (Fixture fixture : getFixtures().values()) {
                    ExternalFixture annotation = fixture.getAnnotation();
                    String name = annotation.name();
                    if (hasRole(annotation, Orchestrator.class)) {
                        TestUtils.declareOrchestrator(configRepo, fixture.getUri().toString(), annotation.credentialId());
                    } else if (hasRole(annotation, Executor.class)) {
                        TestUtils.declareExecutor(configRepo, name, fixture.getUri().toString());
                    }
                }
                base.evaluate();
            }
        };
        Statement externalJenkinsRule = super.apply(inner, d);
        Statement outer = new Statement() {
            @Override public void evaluate() throws Throwable {
                try {
                    configRepo = TestUtils.createConfigRepo();
                    makeNodesLaunchable(configRepo);
                    externalJenkinsRule.evaluate();
                } finally {
                    configRepo.getWorkTree().deleteRecursive();
                }
            }
        };
        return outer;
    }

    private void verifyRole(ExternalFixture fixture) throws AssertionError {
        Class<? extends ExternalFixture.Role>[] roles = fixture.roles();
        Assert.assertEquals("One role expected. got " + Arrays.toString(roles), 1, roles.length);
        Assert.assertTrue("Unknown role " + fixture, hasRole(fixture, Orchestrator.class) || hasRole(fixture, Executor.class));
    }

    @Override
    protected Map<String, ExternalFixture> acceptFixtures(Map<String, ExternalFixture> declaredFixtures, Description description) {
        Map<String, ExternalFixture> accepted = new HashMap<>();
        for (ExternalFixture fixture : declaredFixtures.values()) {
            verifyRole(fixture);

            String[] add = hasRole(fixture, Orchestrator.class)
                    ? new String[] { ORCHESTRATOR }
                    : new String[] { "job-dsl", EXECUTOR }
            ;
            accepted.put(fixture.name(), ExternalFixture.Builder.from(fixture).addInjectPlugins(add).build());
        }
        return accepted;
    }

    @Override
    protected List<String> startWithJvmOptions(List<String> defaults, ExternalFixture fixture) {
        if (hasRole(fixture, Orchestrator.class)) {
            defaults.add("-Dcom.redhat.jenkins.nodesharingbackend.Pool.ENDPOINT=" + configRepo.getWorkTree().getRemote());
            if(fixture.setupEnvCredential()) {
                defaults.add("-Dcom.redhat.jenkins.nodesharingbackend.Pool.USERNAME=jerry");
                defaults.add("-Dcom.redhat.jenkins.nodesharingbackend.Pool.PASSWORD=jerry");
            }
        }
        return defaults;
    }

    @Override
    protected EnvVars startWithEnvVars(EnvVars defaults, ExternalFixture fixture) {
        if (hasRole(fixture, Executor.class)) {
            defaults.put("JCASC_CONFIG_REPO_URL", configRepo.getWorkTree().getRemote());
        }
        return defaults;
    }

    @Override
    protected int fixtureTimeout(ExternalFixture fixture) {
        return 60;
    }

    public interface Orchestrator extends ExternalFixture.Role {}
    public interface Executor extends ExternalFixture.Role {}
}

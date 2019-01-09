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

import com.redhat.jenkins.nodesharing.ActionFailed;
import com.redhat.jenkins.nodesharing.ExternalGridRule;
import com.redhat.jenkins.nodesharingbackend.Api;
import hudson.FilePath;
import jenkins.util.Timer;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ogondza.
 */
public class ExternalJenkinsRule implements TestRule {
    protected final TemporaryFolder tmp;
    private Map<String, Fixture> fixtures = Collections.emptyMap();

    public ExternalJenkinsRule(TemporaryFolder tmp) {
        this.tmp = tmp;
    }

    /**
     * @param name Name of the fixture.
     * @return The object representing the external jenkins insatnce
     * @throws IllegalArgumentException When the name was not declared as fixture by annotations.
     */
    public @Nonnull Fixture fixture(@Nonnull String name) throws IllegalArgumentException {
        if (!fixtures.containsKey(name)) throw new IllegalArgumentException();
        return fixtures.get(name);
    }

    // Callbacks

    // amendJenkinsHome

    /**
     * Process declared fixtures that will be provisioned.
     *
     * @param declaredFixtures Fixtures harvested from annotations.
     * @return Set of fixtures to provision.
     */
    private Map<String, ExternalFixture> acceptFixtures(Map<String, ExternalFixture> declaredFixtures) {
        return declaredFixtures;
    }

    // Internals

    @Override
    public Statement apply(Statement base, final Description d) {
        return new TheStatement(d, base);
    }

    private class TheStatement extends Statement {
        private final Description d;
        private final Statement base;

        public TheStatement(Description d, Statement base) {
            this.d = d;
            this.base = base;
        }

        @Override public void evaluate() throws Throwable {
            Map<String, ExternalFixture> fixtures = acceptFixtures(getDeclaredFixtures());
            if (!fixtures.isEmpty()) {
                ExternalJenkinsRule.this.fixtures = scheduleFixtures(fixtures);
            }
            try {
                base.evaluate();
            } finally {
                // TODO cleanup();
            }
        }

        private @Nonnull Map<String, ExternalFixture> getDeclaredFixtures() {
            ArrayList<Annotation> annotations = new ArrayList<>(d.getAnnotations());
            annotations.addAll(Arrays.asList(getClass().getAnnotations()));
            Map<String, ExternalFixture> fixtures = new HashMap<>();
            for (Annotation a : annotations) {
                if (a instanceof ExternalFixture.Container) {
                    for (ExternalFixture jef : ((ExternalFixture.Container) a).value()) {
                        acceptFixture(fixtures, jef);
                    }
                }
                if (a instanceof ExternalFixture) {
                    ExternalFixture jef = (ExternalFixture) a;
                    acceptFixture(fixtures, jef);
                }
            }
            return fixtures;
        }

        private void acceptFixture(Map<String, ExternalFixture> fixtures, ExternalFixture jef) {
            if (fixtures.containsKey(jef.name())) {
                throw new IllegalArgumentException(String.format(
                        "%s name collision for name '%s' and test '%s'",
                        ExternalFixture.class.getSimpleName(), jef.name(), d.getDisplayName()
                ));
            }
            fixtures.put(jef.name(), jef);
        }

        private Map<String, Fixture> scheduleFixtures(Map<String, ExternalFixture> fixtures) throws IOException, InterruptedException {
            Map<String, Fixture> runningFixtures = new HashMap<>();
            for (ExternalFixture declaredFixture : fixtures.values()) {

                // TODO parallelize

                FilePath jenkinsHome = new FilePath(tmp.newFolder());

                // TODO inject JCasC plugin
                ArrayList<String> injectPlugins = new ArrayList<>();
                injectPlugins.add("configuration-as-code");
                injectPlugins.addAll(Arrays.asList(declaredFixture.injectPlugins()));
                for (String injectPlugin : injectPlugins) {
                    injectPlugin(jenkinsHome, injectPlugin);
                }

                // Inject the JCasC YAML declaration
                try (InputStream yaml = d.getTestClass().getResourceAsStream(declaredFixture.resource())) {
                    if (yaml == null) {
                        throw new IllegalArgumentException(String.format(
                                "Resource not found for fixture '%s': '%s'",
                                declaredFixture.name(), declaredFixture.resource()
                        ));
                    }
                    jenkinsHome.child("jenkins.yaml").copyFrom(yaml);
                }

                File jenkinsWar = ExternalGridRule.getJenkinsWar();
                int port = ExternalGridRule.randomLocalPort();
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", jenkinsWar.getAbsolutePath(), "--httpPort=" + port, "--ajp13Port=-1");
                pb.environment().put("jenkins.install.state", "TEST");
                pb.environment().put("JENKINS_HOME", jenkinsHome.getRemote());

                final File sutLog = new File(String.format(
                        "target/surefire-reports/%s.%s-ExternalFixture-%s.log",
                        d.getClassName(), d.getMethodName(), declaredFixture.name()
                ));
                Files.createDirectories(sutLog.getParentFile().toPath());
                pb.redirectOutput(sutLog);
                pb.redirectErrorStream(true);
                final Process process = pb.start();
//                return Timer.get().schedule(new Callable<URL>() {
//                    @Override public URL call() throws Exception {
//                        for (;;) {
//                            try {
//                                Thread.sleep(5000);
//                                try {
//                                    int i = process.exitValue();
//                                    throw new RuntimeException(String.format("SUT failed with %s, see log in %s%n", i, sutLog));
//                                } catch (IllegalThreadStateException ex) {
//                                    // Alive as expected
//                                }
//                                Api.getInstance().reportUsage(jenkins);
//                                break;
//                            } catch (ActionFailed.RequestFailed ex) {
//                                if (ex.getStatusCode() == 503) continue; // retry
//
//                                throw ex;
//                            } catch (ActionFailed ex) {
//                                // retry
//                            }
//                        }
//                        executorUrls.add(url.toExternalForm());
//                        return url;
//                    }
//                }, 0, TimeUnit.SECONDS);

                runningFixtures.put(declaredFixture.name(), new Fixture(process, jenkinsHome, "http://localhost:" + port + "/"));
            }
            return runningFixtures;
        }

        private void injectPlugin(FilePath jenkinsHome, String name) throws IOException, InterruptedException {
            FilePath plugins = jenkinsHome.child("plugins");
            plugins.mkdirs();
            // Presuming all .jar files on classpath with .hpi siblings are dependencies
            for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
                if (!path.endsWith(".jar")) continue;
                FilePath dependency = new FilePath(new File(path.replaceAll("[.]jar$", ".hpi")));
                if (dependency.exists() && !dependency.getBaseName().equals(name)) {
                    dependency.copyTo(plugins.child(dependency.getName()));
                }
            }
        }
    }

    /**
     * External Jenkins instance controlled by us.
     */
    public static class Fixture {
        private final @Nonnull Process process;
        private final @Nonnull FilePath home;
        private final @Nonnull String url;

        public Fixture(@Nonnull Process process, @Nonnull FilePath home, @Nonnull String url) {
            this.process = process;
            this.home = home;
            this.url = url;
        }

        public @Nonnull FilePath getHome() {
            return home;
        }

        public @Nonnull String getUrl() {
            return url;
        }
    }
}

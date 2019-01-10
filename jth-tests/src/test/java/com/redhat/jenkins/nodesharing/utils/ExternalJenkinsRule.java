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

import com.redhat.jenkins.nodesharing.ExternalGridRule;
import hudson.FilePath;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

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

    public void interactiveBreak() throws Exception {
        for (Fixture f : fixtures.values()) {
            System.out.println(f.getAnnotation().name() + " is running at " + f.getUrl() + " logging to " + f.getLog().getRemote());
        }
        new BufferedReader(new InputStreamReader(System.in)).readLine();
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

                installPlugins(declaredFixture, jenkinsHome);

                injectJcascDefinition(declaredFixture, jenkinsHome);

                Fixture fixture = startFixture(declaredFixture, jenkinsHome);
                runningFixtures.put(declaredFixture.name(), fixture);
            }
            return runningFixtures;
        }

        private @Nonnull Fixture startFixture(ExternalFixture declaredFixture, FilePath jenkinsHome) throws IOException {
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

            return new Fixture(declaredFixture, process, jenkinsHome, new FilePath(sutLog), "http://localhost:" + port + "/");
        }

        private void injectJcascDefinition(ExternalFixture declaredFixture, FilePath jenkinsHome) throws IOException, InterruptedException {
            try (InputStream yaml = d.getTestClass().getResourceAsStream(declaredFixture.resource())) {
                if (yaml == null) {
                    throw new IllegalArgumentException(String.format(
                            "Resource not found for fixture '%s': '%s'",
                            declaredFixture.name(), declaredFixture.resource()
                    ));
                }
                jenkinsHome.child("jenkins.yaml").copyFrom(yaml);
            }
        }

        private void installPlugins(ExternalFixture declaredFixture, FilePath jenkinsHome) throws IOException, InterruptedException {
            Set<String> injectPlugins = new HashSet<>();
            injectPlugins.add("configuration-as-code");
            injectPlugins.addAll(Arrays.asList(declaredFixture.injectPlugins()));
            for (String injectPlugin : injectPlugins) {
                injectPlugin(jenkinsHome, injectPlugin);
            }
        }

        private void injectPlugin(FilePath jenkinsHome, String pluginName) throws IOException, InterruptedException {
            FilePath plugins = jenkinsHome.child("plugins");
            plugins.mkdirs();

            FilePath destination = plugins.child(pluginName + ".hpi");
            if (destination.exists()) return; // Installed already, presumably this dependency is used repeatedly

            // Load from build directory - plugin from the same maven buildroot (multimodule or single)
            if (pluginName.startsWith(".") && pluginName.endsWith(".hpi")) {
System.out.println(new File(".").getAbsolutePath());
                FilePath pluginFile = new FilePath(new File(pluginName)).absolutize();
                if (!pluginFile.exists()) throw new IllegalArgumentException(pluginName + " does not exist in build directory");

                pluginFile.copyTo(destination);
                injectDependencies(jenkinsHome, destination);
                return;
            }

            // Load from classpath as declared maven depndency
            String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
            for (String path : classpath) {
                if (!path.endsWith(".jar")) continue;
                FilePath dependency = new FilePath(new File(path.replaceAll("[.]jar$", ".hpi")));
                if (dependency.exists()){
                    String baseName = dependency.getBaseName();
                    // Dependencies of sibling modules have the name ${ARTIFACTID}.hpi, dependencies in maven repo have the name ${ARTIFACTID}-${VERSION}.hpi
                    if (baseName.equals(pluginName) || baseName.matches(Pattern.quote(pluginName) + "-\\d.*")) {
                        dependency.copyTo(destination);
                        injectDependencies(jenkinsHome, destination);
                        return;
                    }
                }
            }

            throw new IllegalArgumentException(
                    "Plugin " + pluginName + " does not appear to be declared as a maven dependency: " + Arrays.toString(classpath)
            );
        }

        private void injectDependencies(FilePath jenkinsHome, FilePath injectedPlugin) throws IOException, InterruptedException {
            try (JarInputStream jar = new JarInputStream(injectedPlugin.read())) {
                String dependencies = jar.getManifest().getMainAttributes().getValue("Plugin-Dependencies");
                if (dependencies == null) return; // No more deps in this branch
                for (String s : dependencies.split(",")) {
                    if (s.contains("=optional")) continue;
                    String pluginName = s.replaceAll(":.*", "");
                    injectPlugin(jenkinsHome, pluginName);
                }
            }
        }
    }

    /**
     * External Jenkins instance controlled by us.
     */
    public static class Fixture {
        private final @Nonnull ExternalFixture annotation;
        private final @Nonnull Process process;
        private final @Nonnull FilePath home;
        private final @Nonnull FilePath log;
        private final @Nonnull String url;

        public Fixture(@Nonnull ExternalFixture annotation, @Nonnull Process process, @Nonnull FilePath home, @Nonnull FilePath log, @Nonnull String url) {
            this.annotation = annotation;
            this.process = process;
            this.home = home;
            this.log = log;
            this.url = url;
        }

        public @Nonnull FilePath getHome() {
            return home;
        }

        public @Nonnull String getUrl() {
            return url;
        }

        public @Nonnull ExternalFixture getAnnotation() {
            return annotation;
        }

        public @Nonnull FilePath getLog() {
            return log;
        }
    }
}

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

import com.google.common.collect.Lists;
import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.client.JenkinsHttpClient;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Functions;
import hudson.remoting.Which;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestTimedOutException;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.management.ThreadInfo;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

/**
 * Deploy fixtures described by {@link ExternalFixture}.
 *
 * The rule can be subclassed to add custom methods or override callback methods to customize fixture deployment.
 */
public class ExternalJenkinsRule implements TestRule {
    protected final TemporaryFolder tmp;
    private Map<String, Future<Fixture>> fixtures = Collections.emptyMap();

    /**
     * Create the rule based on {@link TemporaryFolder} that needs to be a {@link org.junit.Rule} on the same tet class.
     *
     * public @Rule TemporaryFolder tmp = new TemporaryFolder();
     * public @Rule ExternalJenkinsRule jcr = new ExternalJenkinsRule(tmp);
     */
    public ExternalJenkinsRule(TemporaryFolder tmp) {
        this.tmp = tmp;
    }

    /**
     * @param name Name of the fixture.
     * @return The object representing the external jenkins instance
     * @throws NoSuchElementException When the name was not declared as fixture by annotations.
     */
    public @Nonnull Fixture fixture(@Nonnull String name) throws NoSuchElementException, ExecutionException, InterruptedException, IOException {
        if (!fixtures.containsKey(name)) throw new NoSuchElementException();

        Fixture fixture = fixtures.get(name).get();
        try {
            fixture.waitUntilReady(fixtureTimeout(fixture.getAnnotation()));
        } catch (Exception e) {
            System.err.println(fixture.log.readToString());
            throw new AssertionError(e.getMessage());
        }
        return fixture;
    }

    /**
     * All fixtures running.
     *
     * Note there are not guaranteed to be ready or not failed.
     */
    public @Nonnull Map<String, Fixture> getFixtures() throws ExecutionException, InterruptedException {
        Map<String, Fixture> out = new HashMap<>();
        for (Future<Fixture> value : fixtures.values()) {
            Fixture fixture = value.get();
            out.put(fixture.annotation.name(), fixture);
        }
        return out;
    }

    /**
     * Determine whether the fixture has been decorated with a role.
     *
     * @param fixture The fixture to examine.
     * @param needle Desired role.
     */
    public boolean hasRole(ExternalFixture fixture, Class<? extends ExternalFixture.Role> needle) {
        for (Class<? extends ExternalFixture.Role> role: fixture.roles()) {
            if (needle.isAssignableFrom(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pause the execution leaving the fixtures running.
     */
    public void interactiveBreak() {
        try {
            System.out.println("Pausing executions with following fixtures:");
            for (Future<Fixture> future : fixtures.values()) {
                Fixture f = future.get();
                System.out.println(f.getAnnotation().name() + " is running at " + f.getUri() + " logging to " + f.getLog().getRemote());
            }
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException | InterruptedException | ExecutionException e) {
            throw new Error(e);
        }
    }

    // Callbacks

    // amendJenkinsHome

    /**
     * Process declared fixtures that will be provisioned.
     *
     * @param declaredFixtures Fixtures harvested from annotations.
     * @return Set of fixtures to provision.
     */
    protected Map<String, ExternalFixture> acceptFixtures(Map<String, ExternalFixture> declaredFixtures, Description description) {
        return declaredFixtures;
    }

    /**
     * List of plugins to be installed for all fixtures.
     *
     * @param defaults Rule default plugins to install. Better not remove anything,
     * @param fixture The fixture being installed.
     * @return Amended set of plugin artifactIds to install for every fixture. Removing from the default set is discouraged.
     */
    protected Set<String> initialPlugins(Set<String> defaults, ExternalFixture fixture) {
        return defaults;
    }

    /**
     * Environment variables to pass to Jenkins process.
     *
     * @param defaults Rule default environment variables. Better not remove anything,
     * @return Environment variables to use.
     */
    protected EnvVars startWithEnvVars(EnvVars defaults, ExternalFixture fixture) {
        return defaults;
    }

    /**
     * Properties to pass to JVM.
     *
     * @param defaults Rule default JVM properties. Better not remove anything,
     * @return Properties to use.
     */
    protected List<String> startWithJvmOptions(List<String> defaults, ExternalFixture fixture) {
        return defaults;
    }

    /**
     * Jenkins application arguments.
     *
     * @param defaults Rule default Jenkins arguments. Better not remove anything,
     * @return Arguments to use.
     */
    protected List<String> startWithJenkinsArguments(List<String> defaults, ExternalFixture fixture) {
        return defaults;
    }

    /**
     * Number of seconds to wait for given fixture to be up and running.
     * @param fixture the fixture provisioned.
     * @return Number of seconds to wait.
     */
    protected int fixtureTimeout(ExternalFixture fixture) {
        return 30;
    }

    // Internals

    @Override
    public Statement apply(Statement base, final Description d) {
        return new TheStatement(base, d);
    }

    private class TheStatement extends Statement {
        private final Statement base;
        private final Description d;

        private TheStatement(Statement base, Description d) {
            this.base = base;
            this.d = d;
        }

        @Override public void evaluate() throws Throwable {
            Map<String, ExternalFixture> fixtures = acceptFixtures(getDeclaredFixtures(), d);
            if (!fixtures.isEmpty()) {
                ExternalJenkinsRule.this.fixtures = scheduleFixtures(fixtures);
            }
            try {
                base.evaluate();
            } catch (TestTimedOutException ex) {
                dumpThreads();
                throw ex;
            } finally {
                for (Future<Fixture> fixture : ExternalJenkinsRule.this.fixtures.values()) {
                    terminate(fixture.get());
                }
            }
        }

        // From JenkinsRule
        private void dumpThreads() {
            ThreadInfo[] threadInfos = Functions.getThreadInfos();
            Functions.ThreadGroupMap m = Functions.sortThreadsAndGetGroupMap(threadInfos);
            for (ThreadInfo ti : threadInfos) {
                System.err.println(Functions.dumpThreadInfo(ti, m));
            }
        }

        private void terminate(Fixture fixture) {
            Process p = fixture.process;
            p.destroy();
        }

        private @Nonnull Map<String, ExternalFixture> getDeclaredFixtures() {
            ArrayList<Annotation> annotations = new ArrayList<>(d.getAnnotations());
            annotations.addAll(Arrays.asList(getClass().getAnnotations()));
            Map<String, ExternalFixture> fixtures = new HashMap<>();
            for (Annotation a : annotations) {
                if (a instanceof ExternalFixture.Container) {
                    for (ExternalFixture jef : ((ExternalFixture.Container) a).value()) {
                        addDeclaredFixture(fixtures, jef);
                    }
                }
                if (a instanceof ExternalFixture) {
                    ExternalFixture jef = (ExternalFixture) a;
                    addDeclaredFixture(fixtures, jef);
                }
            }
            return fixtures;
        }

        private void addDeclaredFixture(Map<String, ExternalFixture> fixtures, ExternalFixture jef) {
            if (fixtures.containsKey(jef.name())) {
                throw new IllegalArgumentException(String.format(
                        "%s name collision for name '%s' and test '%s'",
                        ExternalFixture.class.getSimpleName(), jef.name(), d.getDisplayName()
                ));
            }
            fixtures.put(jef.name(), jef);
        }

        private Map<String, Future<Fixture>> scheduleFixtures(Map<String, ExternalFixture> fixtures) {
            Map<String, Future<Fixture>> runningFixtures = new HashMap<>();
            for (ExternalFixture declaredFixture : fixtures.values()) {

                Future<Fixture> ff = Timer.get().schedule(() -> {
                    try {
                        FilePath jenkinsHome = new FilePath(tmp.newFolder());
                        installPlugins(declaredFixture, jenkinsHome);
                        injectJcascDefinition(declaredFixture, jenkinsHome);
                        return startFixture(declaredFixture, jenkinsHome);
                    } catch (IOException|InterruptedException ex) {
                        throw new Error(ex);
                    }
                }, 0, TimeUnit.SECONDS);
                runningFixtures.put(declaredFixture.name(), ff);
            }
            return runningFixtures;
        }

        private @Nonnull Fixture startFixture(ExternalFixture fixture, FilePath jenkinsHome) throws IOException {
            File jenkinsWar = getJenkinsWar();
            int port = randomLocalPort();
            String url = "http://localhost:" + port + "/";

            String java = System.getProperty("java.home").replaceAll("/jre/?$", "") + "/bin/java";
            ArrayList<String> procArgs = new ArrayList<>();
            procArgs.add(java);
            procArgs.addAll(startWithJvmOptions(new ArrayList<>(), fixture));
            procArgs.add("-jar");
            procArgs.add(jenkinsWar.getAbsolutePath());
            procArgs.addAll(startWithJenkinsArguments(Lists.newArrayList("--httpPort=" + port), fixture));

            EnvVars envVars = new EnvVars(
                    "jenkins.install.state", "TEST",
                    "JENKINS_HOME", jenkinsHome.getRemote(),
                    "JCASC_SELF_URL", url
            );

            ProcessBuilder pb = new ProcessBuilder(procArgs);
            pb.environment().putAll(startWithEnvVars(envVars, fixture));

            final File sutLog = allocateLogFile(fixture);
            pb.redirectOutput(sutLog);
            pb.redirectErrorStream(true);
            final Process process = pb.start();

            try {
                return new Fixture(fixture, process, jenkinsHome, new FilePath(sutLog), new URI(url));
            } catch (URISyntaxException e) {
                throw new Error(e);
            }
        }

        private File allocateLogFile(ExternalFixture fixture) throws IOException {
            final File sutLog = new File(String.format(
                    "target/surefire-reports/%s.%s-ExternalFixture-%s.log",
                    d.getClassName(), d.getMethodName(), fixture.name()
            ));
            Files.createDirectories(sutLog.getParentFile().toPath());
            return sutLog;
        }

        private void injectJcascDefinition(ExternalFixture fixture, FilePath jenkinsHome) throws IOException, InterruptedException {
            try (InputStream yaml = d.getTestClass().getResourceAsStream(fixture.resource())) {
                if (yaml == null) {
                    throw new IllegalArgumentException(String.format(
                            "Resource not found for fixture '%s': '%s'",
                            fixture.name(), fixture.resource()
                    ));
                }
                jenkinsHome.child("jenkins.yaml").copyFrom(yaml);

                // TODO remove once the bug is fixed
                // Retrigger JCasC from groovy init script to get the jobs created
                jenkinsHome.child("init.groovy").write("io.jenkins.plugins.casc.ConfigurationAsCode.get().configure()", "UTF-8");
            }
        }

        private void installPlugins(ExternalFixture fixture, FilePath jenkinsHome) throws IOException, InterruptedException {
            Set<String> injectPlugins = new HashSet<>();
            injectPlugins.addAll(Arrays.asList("configuration-as-code"));
            injectPlugins = initialPlugins(injectPlugins, fixture);
            injectPlugins.addAll(Arrays.asList(fixture.injectPlugins()));

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
                FilePath pluginFile = new FilePath(new File(pluginName)).absolutize();
                if (!pluginFile.exists()) throw new IllegalArgumentException(pluginName + " does not exist in build directory");

                destination = plugins.child(pluginFile.getName()); // Recalculate to be correct for local plugin
                pluginFile.copyTo(destination);
                injectDependencies(jenkinsHome, destination);
                return;
            }

            // Load from classpath as declared maven dependency. Resolved by maven-hpi-plugin:resolve-test-dependencies
            String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
            for (String path : classpath) {
                if (!path.endsWith(".jar")) continue;
                FilePath dependency = new FilePath(new File(path.replaceAll("[.]jar$", ".hpi")));
                if (dependency.exists()){
                    String baseName = dependency.getBaseName();
                    // Dependencies in build root have the name ${ARTIFACTID}.hpi, dependencies in maven repo have the name ${ARTIFACTID}-${VERSION}.hpi
                    if (baseName.equals(pluginName) || baseName.matches(Pattern.quote(pluginName) + "-\\d.*")) {
                        dependency.copyTo(destination);
                        injectDependencies(jenkinsHome, destination);
                        return;
                    }
                }
            }

            throw new IllegalArgumentException(
                    "Plugin " + pluginName + " does not appear to be declared as a maven dependency or maven-hpi-plugin:resolve-test-dependencies was not called on this module."
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
    public static class Fixture implements Closeable {
        private final @Nonnull ExternalFixture annotation;
        private final @Nonnull Process process;
        private final @Nonnull FilePath home;
        private final @Nonnull FilePath log;
        private final @Nonnull URI uri;

        private volatile boolean ready = false;
        private final @Nonnull Map<String, JenkinsServer> clients = new HashMap<>();

        public Fixture(@Nonnull ExternalFixture annotation, @Nonnull Process process, @Nonnull FilePath home, @Nonnull FilePath log, @Nonnull URI url) {
            this.annotation = annotation;
            this.process = process;
            this.home = home;
            this.log = log;
            this.uri = url;
        }

        public @Nonnull FilePath getHome() {
            return home;
        }

        public @Nonnull URI getUri() {
            return uri;
        }

        public @Nonnull ExternalFixture getAnnotation() {
            return annotation;
        }

        public @Nonnull FilePath getLog() {
            return log;
        }

        public @Nonnull JenkinsServer getClient(String username, String password) {
            //noinspection IOResourceOpenedButNotSafelyClosed
            return clients.computeIfAbsent(
                    username + System.lineSeparator() + password,
                    k -> new JenkinsServer(new JenkinsHttpClient(uri, getClientBuilder(), username, password))
            );
        }

        public @Nonnull JenkinsServer getClient() {
            //noinspection IOResourceOpenedButNotSafelyClosed
            return clients.computeIfAbsent(
                    "",
                    k -> new JenkinsServer(new JenkinsHttpClient(uri, getClientBuilder()))
            );
        }

        private HttpClientBuilder getClientBuilder() {
            int connectTimeout = 5000;
            RequestConfig config = RequestConfig.custom()
                    .setConnectTimeout(connectTimeout)
                    .setConnectionRequestTimeout(connectTimeout)
                    .setSocketTimeout(connectTimeout)
                    .build();
            return HttpClientBuilder.create().setDefaultRequestConfig(config);
        }

        @Override
        public void close() {
            for (JenkinsServer client : clients.values()) {
                try {
                    client.close();
                } catch (Exception e) {
                    // Resume the loop so the remaining connections are cleaned too
                    e.printStackTrace();
                }
            }
        }

        private void waitUntilReady(int seconds) throws InterruptedException, TimeoutException {
            if (ready) return;

            JenkinsServer client = getClient();
            for (int i = 0; i < seconds; i++) {
                if (!process.isAlive()) {
                    throw new AssertionError("Jenkins " + annotation.name() + " has failed with " + process.exitValue());
                }
                if (client.isRunning()) {
                    ready = true;
                    return;
                }
                Thread.sleep(1000);
            }
            throw new TimeoutException("Fixture " + annotation.name() + " not ready in " + seconds + " seconds");
        }
    }

    private static final AtomicInteger nextLocalPort = new AtomicInteger(49152); // Browse ephemeral range
    public static int randomLocalPort() throws IOException {
        for (;;) {
            int port = nextLocalPort.getAndIncrement();
            if (port >= 65536) throw new IOException("No free ports in whole range?");
            try {
                ServerSocket ss = new ServerSocket(port);
                ss.close();
                return port;
            } catch (IOException ex) {
                // Try another
            }
        }
    }

    // From WarExploder#explode()
    public static File getJenkinsWar() throws IOException {
        File war;
        File core = Which.jarFile(Jenkins.class); // will fail with IllegalArgumentException if have neither jenkins-war.war nor jenkins-core.jar in ${java.class.path}
        String version = core.getParentFile().getName();
        if (core.getName().equals("jenkins-core-" + version + ".jar") && core.getParentFile().getParentFile().getName().equals("jenkins-core")) {
            war = new File(new File(new File(core.getParentFile().getParentFile().getParentFile(), "jenkins-war"), version), "jenkins-war-" + version + ".war");
            if (!war.isFile()) {
                throw new AssertionError(war + " does not yet exist. Prime your development environment by running `mvn validate`.");
            }
        } else {
            throw new AssertionError(core + " is not in the expected location, and jenkins-war-*.war was not in " + System.getProperty("java.class.path"));
        }
        return war;
    }
}

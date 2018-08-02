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

import com.redhat.jenkins.nodesharingbackend.Api;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.remoting.Which;
import hudson.security.ACL;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.util.CopyOnWriteList;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.security.ConfidentialKey;
import jenkins.security.CryptoConfidentialKey;
import jenkins.util.Timer;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import javax.annotation.Nonnull;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Launch Executors and Orchestrators externally to JTH JVM.
 *
 * This adds support to launching sibling JVMs populating them with config taken from the current one.
 *
 * @author ogondza.
 */
public final class ExternalGridRule implements TestRule {
    private final NodeSharingJenkinsRule jenkinsRule;
    private final AtomicInteger nextLocalPort = new AtomicInteger(49152); // Browse ephemeral range
    private final Map<Process, FilePath> executors = new HashMap<>();
    private final CopyOnWriteList<String> executorUrls = new CopyOnWriteList<>();

    public ExternalGridRule(NodeSharingJenkinsRule j) {
        jenkinsRule = j;
    }

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                forceCredentialKeysToBeWritten();
                Throwable fail = null;
                try {
                    // Replace JTH-only AuthorizationStrategy and SecurityRealm that work elsewhere as it would not load on detached executor.
                    Jenkins jenkins = Jenkins.getActiveInstance();
                    jenkins.setNumExecutors(0);

                    GlobalMatrixAuthorizationStrategy gmas = new GlobalMatrixAuthorizationStrategy();
                    jenkins.setAuthorizationStrategy(gmas);
                    gmas.add(Jenkins.READ, "jerry");
                    gmas.add(RestEndpoint.RESERVE, "jerry");
                    gmas.add(Jenkins.READ, ACL.ANONYMOUS_USERNAME);
                    gmas.add(Jenkins.ADMINISTER, "admin");

                    HudsonPrivateSecurityRealm securityRealm = new HudsonPrivateSecurityRealm(true, false, null);
                    jenkins.setSecurityRealm(securityRealm);
                    securityRealm.createAccount("jerry", "jerry").save();
                    securityRealm.createAccount("admin", "admin").save();
                    jenkins.save();

                    base.evaluate();
                } catch (Throwable ex) {
                    fail = ex;
                    throw fail;
                } finally {
                    // Kill all launched executors and delete directories
                    List<Future<Void>> deleteInProgress = new ArrayList<>();
                    for (Map.Entry<Process, FilePath> p : executors.entrySet()) {
                        try {
                            p.getKey().destroy();
                        } catch (Throwable ex) {
                            if (fail == null) {
                                fail = ex;
                            } else {
                                fail.addSuppressed(ex);
                            }
                        }
                        final FilePath path = p.getValue();
                        deleteInProgress.add(Computer.threadPoolForRemoting.submit(new Callable<Void>() {
                            @Override public Void call() throws Exception {
                                path.deleteRecursive();
                                return null;
                            }
                        }));
                    }
                    // Parallelize the deletion
                    for (Future<Void> delete : deleteInProgress) {
                        try {
                            delete.get();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } catch (ExecutionException e) {
                            Throwable ex = e.getCause();
                            if (fail == null) {
                                fail = ex;
                            } else {
                                fail.addSuppressed(ex);
                            }
                        }
                    }
                }
            }
        };
    }

    public ScheduledFuture<URL> executor(final GitClient configRepo) throws Exception {
        return launchSut(configRepo, "node-sharing-executor");
    }

    private @Nonnull ScheduledFuture<URL> launchSut(final GitClient configRepo, String role) throws IOException, InterruptedException {
        final int port = randomLocalPort();
        final URL url = new URL("http://localhost:" + port + "/");
        final ExecutorJenkins jenkins = new ExecutorJenkins(url.toExternalForm(), "executor-" + port);
        // Commit new Jenkins before launching it. Otherwise it will not be in repo by the time it comes up considering itself inactive
        jenkinsRule.addExecutor(configRepo, jenkins);

        FilePath jenkinsHome = new FilePath(File.createTempFile(role, getClass().getSimpleName()));
        jenkinsHome.delete();
        jenkinsHome.mkdirs();

        jenkinsRule.addSharedNodeCloud(configRepo.getWorkTree().getRemote());
        jenkinsRule.jenkins.save();
        FilePath jthJenkinsRoot = jenkinsRule.jenkins.getRootPath();
        jthJenkinsRoot.child("config.xml").copyTo(jenkinsHome.child("config.xml"));
        jenkinsRule.jenkins.clouds.clear();
        jenkinsRule.jenkins.save();

        // Populate executors with jobs copying them from JTH instance
        jthJenkinsRoot.child("jobs").copyRecursiveTo(jenkinsHome.child("jobs"));

        jenkinsHome.child("jenkins.model.JenkinsLocationConfiguration.xml").write(
                "<?xml version='1.0' encoding='UTF-8'?>\n" +
                "<jenkins.model.JenkinsLocationConfiguration>\n" +
                "  <adminAddress>address not configured yet &lt;nobody@nowhere&gt;</adminAddress>\n" +
                "  <jenkinsUrl>" + url.toExternalForm() + "</jenkinsUrl>\n" +
                "</jenkins.model.JenkinsLocationConfiguration>⏎", "UTF-8"
        );

        // Copy users since there is one for authentication
        jthJenkinsRoot.child("users").copyRecursiveTo(jenkinsHome.child("users"));

        // Copy secret keys so Secrets are decryptable
        jthJenkinsRoot.child("secrets").copyRecursiveTo(jenkinsHome.child("secrets"));
        jthJenkinsRoot.child("secret.key").copyTo(jenkinsHome.child("secret.key"));
        jthJenkinsRoot.child("secret.key.not-so-secret").copyTo(jenkinsHome.child("secret.key.not-so-secret"));
        jthJenkinsRoot.child("credentials.xml").copyTo(jenkinsHome.child("credentials.xml"));

        FilePath plugins = jenkinsHome.child("plugins");
        plugins.mkdirs();
        FilePath pluginHpi = new FilePath(new File("../plugin/target/" + role + ".hpi").getAbsoluteFile());
        plugins.child(role + ".hpi").copyFrom(pluginHpi);
        // Presuming all .jar files on classpath with .hpi siblings are dependencies
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!path.endsWith(".jar")) continue;
            FilePath dependency = new FilePath(new File(path.replaceAll("[.]jar$", ".hpi")));
            if (dependency.exists() && !dependency.getBaseName().equals("node-sharing-orchestrator")) {
                dependency.copyTo(plugins.child(dependency.getName()));
            }
        }

        File jar = getJenkinsWar();

        System.out.println("Launching Executor from " + jenkinsHome.getRemote() + " at " + url);

        ProcessBuilder pb = new ProcessBuilder("java", "-jar", jar.getAbsolutePath(), "--httpPort=" + port, "--ajp13Port=-1");
        pb.environment().put("JENKINS_HOME", jenkinsHome.getRemote());
        pb.environment().put("jenkins.install.state", "TEST");
        pb.redirectErrorStream(true);
        final File sutLog = new File("target/surefire-reports/jenkins-" + port);
        sutLog.getParentFile().mkdirs();
        pb.redirectOutput(sutLog);
        final Process process = pb.start();
        executors.put(process, jenkinsHome);
        return Timer.get().schedule(new Callable<URL>() {
            @Override public URL call() throws Exception {
                for (;;) {
                    try {
                        Thread.sleep(5000);
                        try {
                            int i = process.exitValue();
                            throw new RuntimeException(String.format("SUT failed with %s, see log in %s%n", i, sutLog));
                        } catch (IllegalThreadStateException ex) {
                            // Alive as expected
                        }
                        Api.getInstance().reportUsage(jenkins);
                        break;
                    } catch (ActionFailed.RequestFailed ex) {
                        if (ex.getStatusCode() == 503) continue; // retry

                        throw ex;
                    } catch (ActionFailed ex) {
                        // retry
                    }
                }
                executorUrls.add(url.toExternalForm());
                return url;
            }
        }, 0, TimeUnit.SECONDS);
    }

    private int randomLocalPort() throws IOException {
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
    private File getJenkinsWar() throws IOException {
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

    public void interactiveBreak() throws Exception {
        for (String executorUrl : executorUrls) {
            System.out.println("Executor is running at " + executorUrl);
        }
        jenkinsRule.interactiveBreak();
    }

    /**
     * The way jenkins-test-harness works causes JENKINS_HOMEs to be unloadable on its own for second and every other
     * test method and thus unusable for {@link ExternalGridRule}.
     * What happens is the keys get generated during the first test setup, stored to disk and a static field. Subsequent
     * invocations find the static field populated so nothing gets written to disk. Jenkins under test works correctly
     * vast majority of the time but it would not load the same if loaded from the per-test directory. This was observed
     * to cause problems for decrypting secrets so that is what the workaround is focusing on.
     */
    private void forceCredentialKeysToBeWritten() {

        try {
            // Unprotected `Secret.KEY.store(Secret.KEY.getKey());`
            Field keyField = Secret.class.getDeclaredField("KEY");
            keyField.setAccessible(true);
            CryptoConfidentialKey key = (CryptoConfidentialKey) keyField.get(null);

            Method storeMethod = ConfidentialKey.class.getDeclaredMethod("store", byte[].class);
            storeMethod.setAccessible(true);
            Method getKeyMethod = key.getClass().getDeclaredMethod("getKey");
            getKeyMethod.setAccessible(true);
            SecretKeySpec keySpec = (SecretKeySpec) getKeyMethod.invoke(key);
            storeMethod.invoke(key, keySpec.getEncoded());
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    /**
     * Populate config repo to declare JTH an Orchestrator expecting remote Executors.
     *
     * Until executors are added, the config repo is practically invalid as `jenkinses` dir is not committed.
     */
    public GitClient masterGrid(Jenkins jenkins) throws Exception {
        GitClient git = jenkinsRule.getConfigRepo();

        jenkinsRule.makeJthAnOrchestrator(jenkins, git);

        jenkinsRule.declareExecutors(git, Collections.<String, String>emptyMap());
        jenkinsRule.makeNodesLaunchable(git);

        return git;
    }
}

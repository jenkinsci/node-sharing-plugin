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
import com.offbytwo.jenkins.model.BuildResult;
import com.offbytwo.jenkins.model.Job;
import com.offbytwo.jenkins.model.JobWithDetails;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ShareableComputer;
import hudson.FilePath;
import hudson.matrix.AxisList;
import hudson.matrix.LabelExpAxis;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Result;
import hudson.remoting.Which;
import hudson.security.ACL;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.tasks.BuildTrigger;
import hudson.tasks.Shell;
import hudson.triggers.TimerTrigger;
import hudson.util.CopyOnWriteList;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.security.ConfidentialKey;
import jenkins.security.CryptoConfidentialKey;
import jenkins.util.Timer;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;
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
import java.net.URISyntaxException;
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ScalabilityOrchestratorTest {

    private static final String[] MATRIX_AXIS =  new String[] { "0", "1", "2" };

    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();
    @Rule public ExternalGrid grid = new ExternalGrid(j);

    @Test
    public void delegateBuildsToMultipleExecutors() throws Exception {
        GitClient repo = j.singleJvmGrid(j.jenkins);
        String crUrl = repo.getWorkTree().getRemote();

        j.jenkins.setNumExecutors(0);
        MatrixProject win = j.jenkins.createProject(MatrixProject.class, "win");
        win.addTrigger(new TimerTrigger("* * * * *"));
        win.setAxes(new AxisList(
                new LabelExpAxis("label", "windows"),
                new TextAxis("x", MATRIX_AXIS)
        ));
        win.getBuildersList().add(new Shell("sleep 2"));
        win.getPublishersList().add(new BuildTrigger("win", true));

        MatrixProject sol = j.jenkins.createProject(MatrixProject.class, "sol");
        sol.addTrigger(new TimerTrigger("* * * * *"));
        sol.setAxes(new AxisList(
                new LabelExpAxis("label", "solaris10||solaris11"),
                new TextAxis("x", MATRIX_AXIS)
        ));
        sol.getBuildersList().add(new Shell("sleep 0"));
        sol.getPublishersList().add(new BuildTrigger("sol", true));

        Map<String, ScheduledFuture<URL>> launchingExecutors = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            launchingExecutors.put("jenkins" + i, grid.executor(crUrl));
        }
        win.delete();sol.delete();

//        grid.interactiveBreak();

        Map<String, String> executors = new HashMap<>();
        for (Map.Entry<String, ScheduledFuture<URL>> launchingExecutor : launchingExecutors.entrySet()) {
            executors.put(launchingExecutor.getKey(), launchingExecutor.getValue().get().toExternalForm());
        }

        j.writeJenkinses(repo, executors);
        Pool.Updater.getInstance().doRun();
        assertEquals(3, Pool.getInstance().getConfig().getJenkinses().size());

        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(10000);
                verifyBuildWasRun();
                break;
            } catch (AssertionError ex) {
                if (i == 4) throw ex;
                // Retry
            }
        }

        // TODO verify in orchestrator stats once implemented

        // Prevent interrupting running builds causing phony exceptions
        j.jenkins.doQuietDown();
    }

    @Test
    public void restartOrchestrator() throws Exception {
        // Given single executor setup with one build
        GitClient repo = j.singleJvmGrid(j.jenkins);
        String crUrl = repo.getWorkTree().getRemote();

        j.jenkins.setNumExecutors(0);
        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "p");
        FileBuildBlocker blocker = new FileBuildBlocker();
        p.getBuildersList().add(blocker.getShellStep());
        p.setAssignedLabel(Label.get("solaris11"));

        URL executorUrl = grid.executor(crUrl).get();
        p.delete();

        j.writeJenkinses(repo, Collections.singletonMap("foo", executorUrl.toExternalForm()));
        Pool.Updater.getInstance().doRun();
        assertEquals(1, Pool.getInstance().getConfig().getJenkinses().size());

        // When the build is running and one more is queued
        JenkinsServer exec = new JenkinsServer(executorUrl.toURI(), "admin", "admin");
        exec.getJob("p").build();
        Build build = waitForBuildStarted(exec);
        exec.getJob("p").build();

        ShareableComputer computer = j.getComputer("solaris1.acme.com");
        while (computer.isIdle()) { // until queue is propagated and build scheduled
            Thread.sleep(1000);
        }

        // Then it should pick the state up from executors
        //assertEquals(Arrays.asList(j.jenkins.getQueue().getItems()).toString(), 1, j.jenkins.getQueue().getItems().length); // TODO Fails for a known bug
        assertFalse("Build in progress on orchestrator side", computer.isIdle());

        // When orchestrator is restarted
        // TODO use real restart here
        j.jenkins.getQueue().clear();
        for (Node node : j.jenkins.getNodes()) {
            for (Executor executor : node.toComputer().getExecutors()) {
                executor.interrupt(Result.ABORTED);
            }
            j.jenkins.removeNode(node);
        }
        Pool.ensureOrchestratorIsUpToDateWithTheGrid();
        computer = j.getComputer("solaris1.acme.com");
        Thread.sleep(1000);

        // Then it should pick the state up from executors
        //assertEquals(Arrays.asList(j.jenkins.getQueue().getItems()).toString(), 1, j.jenkins.getQueue().getItems().length); // TODO DITTO?
        assertFalse("Build #1 in progress on orchestrator side", computer.isIdle());

        blocker.complete();

        while (build.details().isBuilding()) {
            Thread.sleep(2000);
        }

        assertEquals(BuildResult.SUCCESS, build.details().getResult());

        // TODO DITTO takes a while to propagate the same item to the queue
        //assertEquals(Arrays.asList(j.jenkins.getQueue().getItems()).toString(), 0, j.jenkins.getQueue().getItems().length);
        //assertTrue("Build #2 in progress on orchestrator side", computer.isIdle());

        //grid.interactiveBreak();
    }

    private Build waitForBuildStarted(JenkinsServer exec) throws Exception {
        for (;;) {
            JobWithDetails p = exec.getJob("p");
            Build build = p.getBuildByNumber(1);
            if (build == null) continue;
            if (build.details().isBuilding()) return build;
            Thread.sleep(500);
        }
    }

    private void verifyBuildWasRun(String... jobNames) throws URISyntaxException, IOException {
        for (ExecutorJenkins ej : Pool.getInstance().getConfig().getJenkinses()) {
            JenkinsServer jenkinsServer = new JenkinsServer(ej.getUrl().toURI());
            Map<String, Job> jobs = jenkinsServer.getJobs();
            for (String jobName : jobNames) {
                JobWithDetails job = jobs.get(jobName).details();
                assertThat(job.getNextBuildNumber(), greaterThanOrEqualTo(2));
                Build solBuild = job.getLastFailedBuild();
                if (solBuild != Build.BUILD_HAS_NEVER_RUN) {
                    fail("All builds of " + jobName + " succeeded on " + ej.getUrl() + ":\n" + solBuild.details().getConsoleOutputText());
                }
            }
        }
    }

    /**
     * Create shell build step that can be completed by updating a file.
     */
    public static final class FileBuildBlocker {

        private final FilePath tempFile;

        public FileBuildBlocker() throws IOException, InterruptedException {
            tempFile = new FilePath(File.createTempFile("node-sharing", getClass().getSimpleName()));
            tempFile.write("Created", "UTF-8");
        }

        public Shell getShellStep() {
            return new Shell(
                    "echo 'Started' > '" + tempFile.getRemote() + "'\n" +
                    "while true; do\n" +
                    "  if [ \"$(cat '" + tempFile.getRemote() + "')\" == 'Done' ]; then\n" +
                    "    exit 0\n" +
                    "  fi\n" +
                    "  sleep 1\n" +
                    "done\n"
            );
        }

        public void complete() throws IOException, InterruptedException {
            assertThat(tempFile.readToString(), equalTo("Started\n"));
            tempFile.write("Done", "UTF-8");
        }
    }

    public static final class ExternalGrid implements TestRule {
        private final NodeSharingJenkinsRule jenkinsRule;
        private final AtomicInteger nextLocalPort = new AtomicInteger(49152); // Browse ephemeral range
        private final Map<Process, FilePath> executors = new HashMap<>();
        private final CopyOnWriteList<String> executorUrls = new CopyOnWriteList<>();

        public ExternalGrid(NodeSharingJenkinsRule j) {
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

                        GlobalMatrixAuthorizationStrategy gmas = new GlobalMatrixAuthorizationStrategy();
                        jenkins.setAuthorizationStrategy(gmas);
                        gmas.add(Jenkins.READ, "jerry");
                        gmas.add(RestEndpoint.INVOKE, "jerry");
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

        // Not yet implemented
        public ScheduledFuture<URL> orchestrator(final String configRepo) throws Exception {
            return launchSut(configRepo, "node-sharing-orchestrator");
        }

        public ScheduledFuture<URL> executor(final String configRepo) throws Exception {
            return launchSut(configRepo, "node-sharing-executor");
        }

        private @Nonnull ScheduledFuture<URL> launchSut(final String configRepo, String role) throws IOException, InterruptedException {
            final int port = randomLocalPort();
            final URL url = new URL("http://localhost:" + port + "/");

            FilePath jenkinsHome = new FilePath(File.createTempFile(role, getClass().getSimpleName()));
            jenkinsHome.delete();
            jenkinsHome.mkdirs();

            jenkinsRule.addSharedNodeCloud(configRepo);
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
                    ExecutorJenkins jenkins = new ExecutorJenkins("http://localhost:" + port, "executor-" + port);
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
         * test method and thus unusable for {@link com.redhat.jenkins.nodesharing.ScalabilityOrchestratorTest.ExternalGrid}.
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
    }
}

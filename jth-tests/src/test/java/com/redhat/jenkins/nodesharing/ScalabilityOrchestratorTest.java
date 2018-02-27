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
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.Pool;
import hudson.FilePath;
import hudson.matrix.AxisList;
import hudson.matrix.LabelExpAxis;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.Computer;
import hudson.model.User;
import hudson.remoting.Which;
import hudson.security.ACL;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.tasks.BuildTrigger;
import hudson.tasks.Shell;
import hudson.triggers.TimerTrigger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class ScalabilityOrchestratorTest {

    private static final String[] MATRIX_AXIS =  new String[] { "0", "1", "2" };

    @Rule public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();
    @Rule public ConfigRepoRule configRepo = new ConfigRepoRule();
    @Rule public ExternalGrid grid = new ExternalGrid(j);

    @Test
    public void delegateBuildsToMultipleExecutors() throws Exception {
        GitClient repo = j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
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

//        System.out.println("Sourced from " + j.jenkins.getRootPath());
//        System.out.println(Pool.getInstance().getConfig().getJenkinses());
//        j.interactiveBreak();

        Map<String, String> executors = new HashMap<>();
        for (Map.Entry<String, ScheduledFuture<URL>> launchingExecutor : launchingExecutors.entrySet()) {
            executors.put(launchingExecutor.getKey(), launchingExecutor.getValue().get().toExternalForm());
        }

        configRepo.writeJenkinses(repo, executors);
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

    private void verifyBuildWasRun() throws URISyntaxException, IOException {
        for (ExecutorJenkins ej : Pool.getInstance().getConfig().getJenkinses()) {
            JenkinsServer jenkinsServer = new JenkinsServer(ej.getUrl().toURI());
            Map<String, Job> jobs = jenkinsServer.getJobs();
            JobWithDetails solJob = jobs.get("sol").details();
            assertThat(solJob.getNextBuildNumber(), greaterThanOrEqualTo(2));
            Build solBuild = solJob.getLastFailedBuild();
            if (solBuild != Build.BUILD_HAS_NEVER_RUN) {
                fail("All builds of sol succeeded on " + ej.getUrl() + ":\n" + solBuild.details().getConsoleOutputText());
            }
            JobWithDetails winJob = jobs.get("win").details();
            assertThat(winJob.getNextBuildNumber(), greaterThanOrEqualTo(2));
            Build winBuild = winJob.getLastFailedBuild();
            if (winBuild != Build.BUILD_HAS_NEVER_RUN) {
                fail("All builds of win succeeded on " + ej.getUrl() + ":\n" + winBuild.details().getConsoleOutputText());
            }
        }
    }

    public static final class ExternalGrid implements TestRule {
        private final NodeSharingJenkinsRule jenkinsRule;
        private final AtomicInteger nextLocalPort = new AtomicInteger(49152); // Browse ephemeral range
        private final Map<Process, FilePath> executors = new HashMap<>();

        public ExternalGrid(NodeSharingJenkinsRule j) {
            jenkinsRule = j;
        }

        @Override
        public Statement apply(final Statement base, Description description) {
            return new Statement() {
                @Override public void evaluate() throws Throwable {
                    Throwable fail = null;
                    try {
                        // Replace JTH-only AuthorizationStrategy and SecurityRealm that work elsewhere as it would not load on detached executor.
                        Jenkins jenkins = Jenkins.getActiveInstance();

                        GlobalMatrixAuthorizationStrategy gmas = new GlobalMatrixAuthorizationStrategy();
                        jenkins.setAuthorizationStrategy(gmas);
                        gmas.add(Jenkins.READ, "jerry");
                        gmas.add(RestEndpoint.INVOKE, "jerry");
                        gmas.add(Jenkins.READ, ACL.ANONYMOUS_USERNAME);

                        HudsonPrivateSecurityRealm securityRealm = new HudsonPrivateSecurityRealm(true, false, null);
                        jenkins.setSecurityRealm(securityRealm);
                        User account = securityRealm.createAccount("jerry", "jerry");
                        account.save();
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
            jenkinsRule.jenkins.getRootPath().child("config.xml").copyTo(jenkinsHome.child("config.xml"));
            jenkinsRule.jenkins.clouds.clear();
            jenkinsRule.jenkins.save();

            // Populate executors with jobs copying them from JTH instance
            jenkinsRule.jenkins.getRootPath().child("jobs").copyRecursiveTo(jenkinsHome.child("jobs"));

            jenkinsHome.child("jenkins.model.JenkinsLocationConfiguration.xml").write(
                    "<?xml version='1.0' encoding='UTF-8'?>\n" +
                    "<jenkins.model.JenkinsLocationConfiguration>\n" +
                    "  <adminAddress>address not configured yet &lt;nobody@nowhere&gt;</adminAddress>\n" +
                    "  <jenkinsUrl>" + url.toExternalForm() + "</jenkinsUrl>\n" +
                    "</jenkins.model.JenkinsLocationConfiguration>⏎", "UTF-8"
            );

            // Copy users since there is one for authentication
            jenkinsRule.jenkins.getRootPath().child("users").copyRecursiveTo(jenkinsHome.child("users"));

            // Copy secret keys so Secrets are decryptable
            jenkinsRule.jenkins.getRootPath().child("secrets").copyRecursiveTo(jenkinsHome.child("secrets"));
            jenkinsRule.jenkins.getRootPath().child("credentials.xml").copyTo(jenkinsHome.child("credentials.xml"));

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
    }
}

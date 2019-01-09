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

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.redhat.jenkins.nodesharing.utils.BlockingBuilder;
import com.redhat.jenkins.nodesharingbackend.Pool;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ShareableComputer;
import com.redhat.jenkins.nodesharingbackend.ShareableNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeFactory;
import com.redhat.jenkins.nodesharingfrontend.WorkloadReporter;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.remoting.Which;
import hudson.slaves.CommandLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.OneShotEvent;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;
import org.apache.commons.io.FileUtils;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Assert;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class NodeSharingJenkinsRule extends JenkinsRule {

    public static final ExecutorJenkins DUMMY_OWNER = new ExecutorJenkins("https://jenkins42.acme.com", "jenkins42");
    public static final String USER = "jerry";

    private UsernamePasswordCredentials restCred;
    private MockAuthorizationStrategy mas = new MockAuthorizationStrategy();
    private GitClient configRepo;

    public Statement apply(final Statement base, final Description description) {
        try { // Needs to be done before Jenkins is up
            configRepo = createConfigRepo();
            System.setProperty(Pool.CONFIG_REPO_PROPERTY_NAME, configRepo.getWorkTree().getRemote());
            System.setProperty(Pool.USERNAME_PROPERTY_NAME, USER);
            System.setProperty(Pool.PASSWORD_PROPERTY_NAME, USER);
        } catch (URISyntaxException | IOException e) {
            throw new Error(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Error(e);
        }

        Statement withJenkinsUp = new Statement() {
            @Override public void evaluate() throws Throwable {
                jenkins.setSecurityRealm(createDummySecurityRealm());

                mas.grant(Jenkins.READ, RestEndpoint.RESERVE).everywhere().to("jerry");
                mas.grant(Jenkins.ADMINISTER, RestEndpoint.RESERVE).everywhere().to("admin");
                jenkins.setAuthorizationStrategy(mas);

                restCred = new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, getRestCredentialId(), "Testing node sharing credential", USER, USER
                );
                SystemCredentialsProvider credentialsProvider = SystemCredentialsProvider.getInstance();
                credentialsProvider.getCredentials().add(restCred);
                credentialsProvider.save();

                try {
                    base.evaluate();
                } finally {
                    System.clearProperty(Pool.USERNAME_PROPERTY_NAME);
                    System.clearProperty(Pool.PASSWORD_PROPERTY_NAME);
                    System.clearProperty(Pool.CONFIG_REPO_PROPERTY_NAME);
                    configRepo.getWorkTree().deleteRecursive();
                }
            }
        };
        return super.apply(withJenkinsUp, description);
    }

    public MockAuthorizationStrategy getMockAuthorizationStrategy() {
        return mas;
    }

    protected @Nonnull ShareableComputer getComputer(String name) {
        ShareableComputer shareableComputer = (ShareableComputer) getNode(name).toComputer();
        assert shareableComputer != null;
        return shareableComputer;
    }

    protected @Nonnull ShareableNode getNode(String name) {
        Node node = jenkins.getNode(name);
        assertNotNull("No such node " + name + ". Have: " + jenkins.getNodes(), node);
        return (ShareableNode) node;
    }

    public GitClient getConfigRepo() {
        return configRepo;
    }

    /**
     * Populate config repo making current JVM both Orchestrator and Executor.
     */
    protected GitClient singleJvmGrid(Jenkins jenkins) throws Exception {
        GitClient git = configRepo;

        makeJthAnOrchestrator(jenkins, git);

        declareExecutors(git, Collections.singletonMap("jenkins1", jenkins.getRootUrl()));
        makeNodesLaunchable(git);

        Pool.Updater.getInstance().doRun();
        assertThat(printExceptions(Pool.ADMIN_MONITOR.getErrors()).values(), Matchers.emptyIterable());
        return configRepo;
    }

    public void makeJthAnOrchestrator(Jenkins jenkins, GitClient git) throws IOException, InterruptedException {
        git.getWorkTree().child("config").write("orchestrator.url=" + jenkins.getRootUrl() + System.lineSeparator() + "enforce_https=false", "UTF-8");
        git.add("config");
        git.commit("Writing config repo config");
    }

    // Make the nodes launchable by turning the xml to node, decorating it and turning it back to xml again
    public void makeNodesLaunchable(GitClient git) throws IOException, InterruptedException {
        final File slaveJar = Which.jarFile(hudson.remoting.Launcher.class).getAbsoluteFile();
        for (FilePath xmlNode : git.getWorkTree().child("nodes").list("*.xml")) {
            SharedNode node = SharedNodeFactory.transform(NodeDefinition.Xml.create(xmlNode));
            node.setLauncher(new CommandLauncher(
                    System.getProperty("java.home") + "/bin/java -jar " + slaveJar
            ));
            try (OutputStream out = xmlNode.write()) {
                Jenkins.XSTREAM2.toXMLUTF8(node, out);
            }
        }
        git.add("nodes");
        git.commit("Making nodes in config repo launchable");
    }

    // TODO should not be needed as TaskLog.TaskFailed was fixed to print itself
    public Map<String, String> printExceptions(Map<String, Throwable> values) throws IOException, InterruptedException {
        Map<String, String> out = new HashMap<>(values.size());
        for (Map.Entry<String, Throwable> entry : values.entrySet()) {
            Throwable value = entry.getValue();
            String throwable = value instanceof TaskLog.TaskFailed
                    ? ((TaskLog.TaskFailed) value).getLog().readContent()
                    : Functions.printThrowable(value)
            ;
            out.put(entry.getKey(), throwable);
        }
        return out;
    }

    public static GitClient createConfigRepo() throws URISyntaxException, IOException, InterruptedException {
        File orig = new File(NodeSharingJenkinsRule.class.getResource("dummy_config_repo").toURI());
        Assert.assertTrue(orig.isDirectory());
        File repo = File.createTempFile("jenkins.nodesharing", NodeSharingJenkinsRule.class.getSimpleName());
        assert repo.delete();
        assert repo.mkdir();
        FileUtils.copyDirectory(orig, repo);

        StreamTaskListener listener = new StreamTaskListener(System.err, Charset.defaultCharset());
        // To make it work with no gitconfig
        String name = "Pool Maintainer";
        String mail = "pool.maintainer@acme.com";
        EnvVars env = new EnvVars("GIT_AUTHOR_NAME", name, "GIT_AUTHOR_EMAIL", mail, "GIT_COMMITTER_NAME", name, "GIT_COMMITTER_EMAIL", mail);
        GitClient git = Git.with(listener, env).in(repo).using("git").getClient();
        git.init();
        git.add("*");
        git.commit("Init");
        return git;
    }

    /**
     * Write local urls of Jenkinses
     */
    public void declareExecutors(GitClient git, Map<String, String> jenkinses) throws InterruptedException, IOException {
        FilePath jenkinsesDir = git.getWorkTree().child("jenkinses");
        for (FilePath filePath : jenkinsesDir.list()) {
            filePath.delete();
        }
        for (Map.Entry<String, String> j : jenkinses.entrySet()) {
            String url = j.getValue();
            jenkinsesDir.child(j.getKey()).write(getJenkinsfileContent(url), "UTF-8");
        }
        git.add("jenkinses");
        git.commit("Update Jenkinses");
    }

    @Nonnull private String getJenkinsfileContent(String url) {
        StringBuilder sb = new StringBuilder("url=").append(url).append(System.lineSeparator());
        if (url.startsWith("http://")) {
            sb.append("enforce_https=false").append(System.lineSeparator());
        }
        return sb.toString();
    }

    public void addExecutor(GitClient git, ExecutorJenkins j) throws IOException, InterruptedException {
        FilePath jenkinsFile = git.getWorkTree().child("jenkinses").child(j.getName());
        assert !jenkinsFile.exists();

        jenkinsFile.write(getJenkinsfileContent(j.getUrl().toExternalForm()), "UTF-8");

        git.add("jenkinses");
        git.commit("Add Jenkins");
    }

    void disableLocalExecutor(GitClient gitClient) throws Exception {
        // Replace the inner Jenkins with one from different URL as removing the file would cause git to remove the empty
        // directory breaking repo validation
        declareExecutors(gitClient, singletonMap("this-one", getURL() + "/defunc"));
        Pool.Updater.getInstance().doRun();
    }

    public UsernamePasswordCredentials getRestCredential() {
        return restCred;
    }
    public String getRestCredentialId() {
        return "rest-cred-id";
    }

    protected List<ReservationTask> getQueuedReservations() {
        ArrayList<ReservationTask> out = new ArrayList<>();
        for (Queue.Item item : jenkins.getQueue().getItems()) {
            if (item.task instanceof ReservationTask) {
                out.add((ReservationTask) item.task);
            }
        }
        Collections.reverse(out);
        return out;
    }

    protected List<ReservationTask.ReservationExecutable> getActiveReservations() {
        ArrayList<ReservationTask.ReservationExecutable> out = new ArrayList<>();
        for (Computer c : jenkins.getComputers()) {
            if (c instanceof ShareableComputer) {
                ReservationTask.ReservationExecutable reservation = ((ShareableComputer) c).getReservation();
                if (reservation == null) continue;
                out.add(reservation);
            }
        }
        return out;
    }

    public void startDanglingReservation(ExecutorJenkins executor, ShareableNode node) throws InterruptedException, java.util.concurrent.ExecutionException {
        assertNull(node.getComputer().getReservation());
        // Using backfill tasks to prevent utilizeNode calls to fail as the Executor might not be real
        new ReservationTask(executor, node.getNodeName(), true).schedule().getFuture().getStartCondition().get();
        assertNotNull(node.getComputer().getReservation());
    }

    public ShareableNode getSomeShareableNode() {
        return ShareableNode.getAll().values().iterator().next();
    }

    public ExecutorJenkins getSomeExecutor() {
        return Pool.getInstance().getConfig().getJenkinses().iterator().next();
    }

    public @Nonnull BlockingBuilder getBlockingProject(String label) throws IOException {
        BlockingBuilder bb = getBlockingProject();
        bb.getProject().setAssignedLabel(Label.get(label));
        return bb;
    }

    public @Nonnull BlockingBuilder getBlockingProject(Node node) throws IOException {
        BlockingBuilder bb = getBlockingProject();
        bb.getProject().setAssignedNode(node);
        return bb;
    }

    @Nonnull private BlockingBuilder getBlockingProject() throws IOException {
        FreeStyleProject p = createFreeStyleProject();
        BlockingBuilder bb = new BlockingBuilder(p);
        p.getBuildersList().add(bb);
        return bb;
    }

    protected static class BlockingTask extends MockTask {
        final OneShotEvent running = new OneShotEvent();
        final OneShotEvent done = new OneShotEvent();

        BlockingTask(Label label) {
            super(DUMMY_OWNER, label);
        }

        @Override public void perform() {
            running.signal();
            try {
                done.block();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Proceed
            }
        }
    }

    /**
     * Mock task to represent fake reservation task. To be run on orchestrator only.
     */
    protected static class MockTask extends ReservationTask {
        final ShareableComputer actuallyRunOn[] = new ShareableComputer[1];
        MockTask(@Nonnull ExecutorJenkins owner, @Nonnull Label label) {
            super(owner, label, "MockTask", 1L);
        }

        @Override
        public @CheckForNull Queue.Executable createExecutable() {
            return new ReservationExecutable(this) {
                @Override
                public void run() throws AsynchronousExecution {
                    actuallyRunOn[0] = (ShareableComputer) Executor.currentExecutor().getOwner();
                    perform();
                }
            };
        }

        public void perform() {
            // NOOOP until overriden
        }
    }

    /**
     * Create a new {@link SharedNodeCloud} instance and attach it to jenkins
     *
     * @param configRepoUrl URL for mocked Config repo.
     * @return created {@link SharedNodeCloud} instance
     */
    @Nonnull
    public SharedNodeCloud addSharedNodeCloud(@Nonnull final String configRepoUrl) {
        SharedNodeCloud cloud = new SharedNodeCloud(configRepoUrl, getRestCredentialId());
        jenkins.clouds.add(cloud);
        return cloud;
    }

    static class BlockingCommandLauncher extends CommandLauncher {
        final OneShotEvent start = new OneShotEvent();
        final OneShotEvent end = new OneShotEvent();

        BlockingCommandLauncher(String command) {
            super(command);
        }

        @Override
        public void launch(SlaveComputer computer, final TaskListener listener) {
            start.signal();
            try {
                end.block();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            super.launch(computer, listener);
        }
    }

    /**
     * Trigger workload update now from executor
     */
    protected void reportWorkloadToOrchestrator() {
        WorkloadReporter.Detector.all().get(WorkloadReporter.Detector.class).run();
    }
}

package com.redhat.jenkins.nodesharingfrontend;

import com.redhat.jenkins.nodesharing.ConfigRepo;
import com.redhat.jenkins.nodesharing.ConfigRepoAdminMonitor;
import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import com.redhat.jenkins.nodesharing.NodeDefinition;
import com.redhat.jenkins.nodesharing.TaskLog;
import com.redhat.jenkins.nodesharing.transport.DiscoverResponse;
import com.redhat.jenkins.nodesharing.transport.NodeStatusResponse;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.PeriodicWork;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.anyOf;
import static com.cloudbees.plugins.credentials.CredentialsMatchers.instanceOf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;

/**
 * Shared Node Cloud implementation.
 */
public class SharedNodeCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(SharedNodeCloud.class.getName());

    private static final ConfigRepoAdminMonitor ADMIN_MONITOR = new ConfigRepoAdminMonitor();

    /** Git cloneable URL of config repository. */
    private @Nonnull String configRepoUrl;

    /** Credentials ID for orchestrator REST communication */
    private @Nonnull String orchestratorCredentialsId;

    /** The id of the ssh credentials for hosts. */
    private String sshCredentialsId;

    /**
     * The time in seconds to attempt to establish a SSH connection.
     */
    private Integer sshConnectionTimeOut;

    private transient Api api = null;

    private transient @Nullable ConfigRepo configRepo; // Null after deserialization until getConfigRepo is called
    private transient @CheckForNull ConfigRepo.Snapshot latestConfig; // Null when not yet obtained or there ware errors while doing so

    /**
     * Constructor for Config Page.
     *
     * @param configRepoUrl ConfigRepo url
     * @param orchestratorCredentialsId Orchestrator credential.
     * @param sshCredentialsId Creds to use to connect to slave.
     * @param sshConnectionTimeOut timeout for SSH connection in secs.
     */
    @DataBoundConstructor
    public SharedNodeCloud(@Nonnull String configRepoUrl, @Nonnull String orchestratorCredentialsId, @Nonnull String sshCredentialsId, Integer sshConnectionTimeOut) {
        super(ExecutorJenkins.inferCloudName(configRepoUrl));

        this.configRepoUrl = configRepoUrl;
        this.orchestratorCredentialsId = orchestratorCredentialsId;
        this.configRepo = getConfigRepo();
        this.sshCredentialsId = sshCredentialsId;
        this.sshConnectionTimeOut = sshConnectionTimeOut;
    }

    /**
     * Get associated API object.
     *
     * @throws IllegalStateException In case cloud is not able to obtain the config.
     */
    public final @Nonnull Api getApi() throws IllegalStateException {
        if (this.api == null) {
            ConfigRepo.Snapshot latestConfig = getLatestConfig();
            if (latestConfig == null) throw new IllegalStateException("No latest config found");
            this.api = new Api(latestConfig, configRepoUrl, this);
        }
        return this.api;
    }

    /**
     * Get Cloud name.
     *
     * @return name.
     */
    @Nonnull
    public String getName() {
        return name;
    }

    /**
     * Get Config repo url.
     *
     * @return configRepoUrl.
     */
    @Nonnull
    public String getConfigRepoUrl() {
        return configRepoUrl;
    }

    /**
     * Get SSH connection time in seconds.
     *
     * @return timeout in secs.
     */
    @Nonnull
    public Integer getSshConnectionTimeOut() {
        return sshConnectionTimeOut;
    }

    /**
     * Get credentials for SSH connection.
     *
     * @return credential id.
     */
    @Nonnull @Restricted(DoNotUse.class) // View Only
    public String getSshCredentialsId() {
        return sshCredentialsId;
    }

    @Nonnull
    public String getOrchestratorCredentialsId() {
        return orchestratorCredentialsId;
    }

    /**
     * Setter for credentialsId.
     *
     * @param sshCredentialsId to use to connect to slaves with.
     */
    @DataBoundSetter
    public void setSshCredentialsId(@Nonnull final String sshCredentialsId) {
        this.sshCredentialsId = sshCredentialsId;
    }

    private @Nonnull ConfigRepo getConfigRepo() {
        synchronized (this) { // Prevent several ConfigRepo instances to be created over same directory
            if (configRepo != null) return configRepo;

            FilePath configRepoDir = Jenkins.getActiveInstance().getRootPath().child("node-sharing/configs/" + name);
            return configRepo = new ConfigRepo(configRepoUrl, new File(configRepoDir.getRemote()));
        }
    }

    /**
     * Get latest config repo snapshot.
     *
     * @return Snapshot or null when there are problems reading it.
     */
    @CheckForNull
    public ConfigRepo.Snapshot getLatestConfig() {
        if (latestConfig == null) {
            try {
                updateConfigSnapshot();
            } catch (InterruptedException e) {
                // Set interruption bit for later
                Thread.currentThread().interrupt();
            }
        }
        return latestConfig;
    }

    private void updateConfigSnapshot() throws InterruptedException {
        try {
            latestConfig = getConfigRepo().getSnapshot();
        } catch (IOException|TaskLog.TaskFailed ex) {
            ADMIN_MONITOR.report(configRepoUrl, ex);
            LOGGER.log(Level.SEVERE, "Failed updating config", ex);
        }
    }

    @Extension
    public static class ConfigRepoUpdater extends PeriodicWork {

        @Override public long getRecurrencePeriod() {
            return 5 * MIN;
        }

        @Override protected void doRun() throws Exception {
            ADMIN_MONITOR.clear();
            for (SharedNodeCloud cloud : getAll()) {
                cloud.updateConfigSnapshot();

                // TODO Check and fire cfg. was changed if necessary
            }
        }
    }

    /**
     * Make unique name per Cloud.
     *
     * @param nodeName node name from the config repo.
     * @return the node name.
     */
    @Nonnull
    public String getNodeName(@Nonnull final String nodeName) {
        return nodeName + "-" + name;
    }

    /**
     * Get the node status.
     *
     * @param nodeName The node name.
     * @return The node status.
     */
    @Nonnull
    public NodeStatusResponse.Status getNodeStatus(@Nonnull final String nodeName) {
        NodeStatusResponse.Status status = NodeStatusResponse.Status.NOT_FOUND;
        Computer computer = Jenkins.getActiveInstance().getComputer(getNodeName(nodeName));
        if (computer != null && computer instanceof SharedComputer) {
            status = NodeStatusResponse.Status.FOUND;
            if (computer.isIdle() && !computer.isConnecting()) {
                status = NodeStatusResponse.Status.IDLE;
            } else if (computer.isOffline() && !computer.isIdle()) {
                // Offline but BUSY
                status = NodeStatusResponse.Status.OFFLINE;
            } else if (!computer.isIdle()) {
                status = NodeStatusResponse.Status.BUSY;
            } else  if (computer.isConnecting()) {
                status = NodeStatusResponse.Status.CONNECTING;
            }
        }
        return status;
    }

    public SharedNode createNode(@Nonnull final NodeDefinition definition) {
        SharedNode node = SharedNodeFactory.transform(definition);
        final String nodeName = definition.getName();
        node.init(new ProvisioningActivity.Id(name, null, getNodeName(nodeName)));
        assert CloudStatistics.get().getActivityFor(node.getId()) != null;
        return node;
    }

//    /**
//     * Get the run status.
//     *
//     * @param runId The run id.
//     * @return The run status.
//     */
//    @Nonnull
//    public RunStatusResponse.Status getRunStatus(final long runId) {
//        RunStatusResponse.Status status = RunStatusResponse.Status.NOT_FOUND;
//        Queue.Item item = Jenkins.getActiveInstance().getQueue().getItem(runId);
//        if (item != null) {
//            status = RunStatusResponse.Status.FOUND;
//            if (item.isBlocked()) {
//                status = RunStatusResponse.Status.BLOCKED;
//            } else if (item.isStuck()) {
//                status = RunStatusResponse.Status.STUCK;
//            } else if (item.getFuture().isDone()) {
//                status = RunStatusResponse.Status.DONE;
//            }
//            // TODO Extract EXECUTING
//        }
//        return status;
//    }

    // Rely on content of ConfigRepo and not what Orchestrator advertises simply as it is less fragile. No strong preference otherwise.
    @Override
    public boolean canProvision(Label label) {
        ConfigRepo.Snapshot latestConfig = getLatestConfig();
        if (latestConfig == null) {
            return false;
        }

        for (NodeDefinition node : latestConfig.getNodes().values()) {
            if (label != null && label.matches(node.getLabelAtoms())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nonnull Collection<PlannedNode> provision(@CheckForNull final Label label, final int excessWorkload) {
        // The nodes are not delivered through PlannedNodes so this is always empty.
        // TODO other clouds will try to deliver this while we are trying as well.
        return Collections.emptyList();
    }

    /**
     * Get Shared node cloud using provided name.
     *
     * @param name Cloud name.
     * @return a Shared node cloud or null if not found.
     */
    @CheckForNull
    public static SharedNodeCloud getByName(@Nonnull final String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getActiveInstance().clouds.getByName(name);
        if (cloud instanceof SharedNodeCloud) {
            return (SharedNodeCloud) cloud;
        }
        return null;
    }

    /**
     * Get all configured {@link SharedNodeCloud}s.
     */
    public static @Nonnull Collection<SharedNodeCloud> getAll() {
        ArrayList<SharedNodeCloud> out = new ArrayList<>();
        for (Cloud cloud : Jenkins.getActiveInstance().clouds) {
            if (cloud instanceof SharedNodeCloud) {
                out.add((SharedNodeCloud) cloud);
            }
        }
        return out;
    }

    /**
     * The cloud is considered operational once it can get data from Config Repo and talk to orchestrator.
     */
    public boolean isOperational() {
        return latestConfig != null;
    }

    /**
     * Descriptor for Cloud.
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Shared Nodes";
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withMatching(anyOf(
                            instanceOf(SSHUserPrivateKey.class),
                            instanceOf(UsernamePasswordCredentials.class)),
                            CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class));
        }

        /**
         * Test connection.
         *
         * @param configRepoUrl Config repository URL.
         * @return Form Validation.
         * @throws ServletException if occurs.
         */
        @Restricted(DoNotUse.class)
        public FormValidation doTestConnection(
                @Nonnull @QueryParameter("configRepoUrl") String configRepoUrl,
                @Nonnull @QueryParameter("orchestratorCredentialsId") String restCredentialId
        ) throws Exception {
            try {
                new URI(configRepoUrl);
            } catch (URISyntaxException e) {
                return FormValidation.error(e, Messages.InvalidURI());
            }

            FilePath testConfigRepoDir = Jenkins.getActiveInstance().getRootPath().child("node-sharing/configs/testNewConfig");
            try {
                SharedNodeCloud cloud = new SharedNodeCloud(configRepoUrl, restCredentialId, "", null);
                Api api = new Api(cloud.getConfigRepo().getSnapshot(), configRepoUrl, cloud);
                DiscoverResponse discover = api.discover();
                if (!discover.getDiagnosis().isEmpty()) {
                    return FormValidation.warning(discover.getDiagnosis());
                }
                return FormValidation.okWithMarkup("<strong>" + Messages.TestConnectionOK(discover.getVersion()) + "<strong>");
            } catch (TaskLog.TaskFailed e) {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                try (OutputStreamWriter writer = new OutputStreamWriter(bout, Charset.defaultCharset())) {
                    writer.write("<pre>");
                    e.getLog().getAnnotatedText().writeHtmlTo(0, writer);
                    writer.write("</pre>");
                }
                return FormValidation.errorWithMarkup(bout.toString(Charset.defaultCharset().name()));
            } catch (Exception e) {
                e.printStackTrace();
                return FormValidation.error(e, "Test failed");
            } finally {
                testConfigRepoDir.deleteRecursive();
            }
        }
    }
}

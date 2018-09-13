package com.redhat.jenkins.nodesharingfrontend;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.model.Queue.BuildableItem;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Phase.COMPLETED;

/**
 * Shared execution node.
 */
public class SharedNode extends AbstractCloudSlave implements EphemeralNode, TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(SharedNode.class.getName());

    private static final long serialVersionUID = -3284884519464420953L;
    private static final CauseOfBlockage COB_NO_FLYWEIGHTS = new CauseOfBlockage() {
        @Override public String getShortDescription() { return "Cannot build flyweight tasks"; }
    };
    private static final CauseOfBlockage COB_NO_RESERVATIONS = new CauseOfBlockage() {
        @Override public String getShortDescription() { return "ReservationTasks should not run here"; }
    };

    private boolean skipWipeout;

    @Nonnull
    private ProvisioningActivity.Id id;
    @Nonnull
    private String hostname;

    // Never used, the class is always created from NodeDefinition. See: SharedNodeCloud#createNode()
    @Restricted(DoNotUse.class)
    private SharedNode(
            String name, String nodeDescription, String remoteFS, int numExecutors, Mode mode, String labelString, ComputerLauncher launcher, RetentionStrategy retentionStrategy, List<? extends NodeProperty<?>> nodeProperties
    ) throws FormException, IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
    }

    /*package*/ void init(@Nonnull final ProvisioningActivity.Id id) {
        this.id = id;
        // Name of the node as defined is its hostname, but we are changing the name of the Jenkins node as we need to
        // preserve the old value as hostname
        hostname = name;
        name = id.getNodeName();

        // Make a current phase of provisioning activity failed if exists for any node with the same name
        for (ProvisioningActivity a : CloudStatistics.get().getNotCompletedActivities()) {
            if (Objects.equals(name, a.getId().getNodeName()) && a.getCurrentPhase() != COMPLETED){
                PhaseExecutionAttachment attachment = new PhaseExecutionAttachment(
                        ProvisioningActivity.Status.FAIL,
                        "Provisioning activity have not completed before the node was reserved again!"
                );
                CloudStatistics.get().attach(a, a.getCurrentPhase(), attachment);
                a.enterIfNotAlready(COMPLETED);
            }
        }
        CloudStatistics.ProvisioningListener.get().onStarted(id);
    }

    @Override
    @Nonnull
    public AbstractCloudComputer<?> createComputer() {
        return new SharedComputer(this);
    }

    @Override
    @CheckForNull
    public CauseOfBlockage canTake(BuildableItem item) {
        if (item.task instanceof Queue.FlyweightTask) {
            return COB_NO_FLYWEIGHTS;
        }
        if ("com.redhat.jenkins.nodesharingbackend.ReservationTask".equals(item.task.getClass().getName())) { // jth-tests hack
            return COB_NO_RESERVATIONS;
        }
        return super.canTake(item);
    }

    @Override
    protected void _terminate(TaskListener listener) throws InterruptedException {
        SharedNodeCloud cloud = SharedNodeCloud.getByName(id.getCloudName());
        if (cloud != null) { // Might be deleted or using different config repo
            // Wipeout the workspace content if necessary but left untouched workspace itself
            if (!skipWipeout) {
                LOGGER.info(getNodeName() + ": wipeout activated");
                if (listener != null) {
                    listener.getLogger().println("Wipeout: Is activated");
                }
                try {
                    final FilePath workspace = getWorkspaceRoot();
                    if (listener != null) {
                        listener.getLogger().println("Wipeout: Starting");
                    }
                    workspace.deleteContents();
                    if (listener != null) {
                        listener.getLogger().println("Wipeout: Finished");
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            getNodeName() + ": Unexpected IOException occurred during wipeout workspace content: ", e);
                    if (listener != null) {
                        listener.getLogger().println("Wipeout: Unexpected IOException occurred during wipeout workspace content:");
                        listener.getLogger().println(e.toString());
                    }
                } catch (InterruptedException e) {
                    LOGGER.log(Level.WARNING,
                            getNodeName() + ": Wipeout interrupted!");
                    if (listener != null) {
                        listener.getLogger().println("Wipeout: interrupted during wipeouting the workspace content:");
                    }
                    Thread.currentThread().interrupt();
                }
            }
            // If AsyncResourceDisposer is available, process all Disposables first
            AsyncResourceDisposer disposer =
                    (AsyncResourceDisposer) Jenkins.getActiveInstance().getAdministrativeMonitor("AsyncResourceDisposer");
            if (disposer != null) {
                // If there is a tracked work to be done yet reschedule all items and wait for 30s
                if (!disposer.getBacklog().isEmpty())  {
                    disposer.reschedule();
                    Thread.sleep(30000);
                }
            }

            cloud.getApi().returnNode(this);
        }
    }

    @Nonnull
    public String getHostName() {
        return hostname;
    }

    @Override
    @Nonnull
    public ProvisioningActivity.Id getId() {
        return id;
    }

    @Nonnull
    public Node asNode() {
        return this;
    }

    /**
     * Slave descriptor.
     */
    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        @Nonnull
        public String getDisplayName() {
            return "Shared Node";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}

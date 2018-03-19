package com.redhat.jenkins.nodesharingfrontend;

import hudson.Extension;
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
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

import java.util.Objects;
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

    private @Nonnull ProvisioningActivity.Id id;
    private @Nonnull String hostname;

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
    public AbstractCloudComputer<?> createComputer() {
        return new SharedComputer(this);
    }

    @Override
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
    protected void _terminate(TaskListener listener) {
        ProvisioningActivity activity = CloudStatistics.get().getActivityFor(this);
        if (activity != null) {
            activity.enterIfNotAlready(COMPLETED);
        }

        SharedNodeCloud cloud = SharedNodeCloud.getByName(id.getCloudName());
        if (cloud != null) { // Might be deleted or using different config repo
            cloud.getApi().returnNode(this);
        }
    }

    public @Nonnull String getHostName() {
        return hostname;
    }

    @Override
    public @Nonnull ProvisioningActivity.Id getId() {
        return id;
    }

    public @Nonnull Node asNode() {
        return this;
    }

    /**
     * Slave descriptor.
     */
    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Shared Node";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}

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
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.util.List;

import java.util.logging.Logger;

/**
 * Shared execution node.
 */
public class SharedNode extends AbstractCloudSlave implements EphemeralNode, TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(SharedNode.class.getName());
    private static final int NUM_EXECUTORS = 1;

    private static final long serialVersionUID = -3284884519464420953L;

    private ProvisioningActivity.Id id;

    /**
     * Shared Node.
     *
     * @param id             id of the provisioning attempt.
     * @param label          Jenkins label requested.
     * @param remoteFS       Remote FS root.
     * @param launcher       Slave launcher.
     * @param strategy       Retention Strategy.
     * @param nodeProperties node props.
     * @throws FormException if occurs.
     * @throws IOException   if occurs.
     */
    public SharedNode(
            ProvisioningActivity.Id id,
            String label,
            String remoteFS,
            ComputerLauncher launcher,
            RetentionStrategy<AbstractCloudComputer> strategy,
            List<? extends NodeProperty<?>> nodeProperties) throws FormException, IOException {
        //CS IGNORE check FOR NEXT 3 LINES. REASON: necessary inline conditional in super().
        super(id.getNodeName() + '.' + id.getCloudName(), "", remoteFS, NUM_EXECUTORS,
                label == null ? Node.Mode.NORMAL : Node.Mode.EXCLUSIVE,
                label, launcher, strategy, nodeProperties);
        this.id = id;
        LOGGER.info("Instantiating a new SharedNode: name='" + name + "', label='"
                + (label == null ? "<NULL>" : label) + "'");
    }

    @Override
    public AbstractCloudComputer<?> createComputer() {
        return new SharedComputer(this);
    }

    @Override
    public CauseOfBlockage canTake(BuildableItem item) {
        if (item.task instanceof Queue.FlyweightTask) {
            return new CauseOfBlockage() {
                @Override
                public String getShortDescription() {
                    return "Cannot build flyweight tasks on " + name;
                }
            };
        }
        return super.canTake(item);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        ProvisioningActivity activity = CloudStatistics.get().getActivityFor(this);
        if (activity != null) {
            activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
        }

        LOGGER.finer("Adding the host '" + name + "' to the disposable queue.");
        SharedNodeCloud cloud = SharedNodeCloud.getByName(id.getCloudName());
        if (cloud != null) { // Might be deleted or using different config repo
            cloud.getApi().returnNode(this);
        }
    }

    @Nonnull
    public String getCloudName() {
        return id.getCloudName();
    }

    @Override
    public ProvisioningActivity.Id getId() {
        return id;
    }

    public void setId(@Nonnull final ProvisioningActivity.Id id) {
        this.id = id;
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
        public String getDisplayName() {
            return "Shared Node";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}

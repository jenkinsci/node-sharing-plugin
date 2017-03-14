package com.redhat.foreman;

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
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

import java.util.logging.Logger;

/**
 * Foreman Shared Node.
 */
public class ForemanSharedNode extends AbstractCloudSlave implements EphemeralNode, TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(ForemanSharedNode.class.getName());
    private static final int NUM_EXECUTORS = 1;

    private static final long serialVersionUID = -3284884519464420953L;

    @Deprecated // Use id instead,
    private String cloudName;
    private ProvisioningActivity.Id id;

    /**
     * Foreman Shared Node.
     *
     * @param id             id of the provisioning attempt.
     * @param label          Jenkins label requested.
     * @param remoteFS       Remote FS root.
     * @param launcher       Slave launcher.
     * @param strategy       Retention Strategy.
     * @param nodeProperties node props.
     * @throws FormException if occurs.
     */
    public ForemanSharedNode(
            ProvisioningActivity.Id id,
            String label,
            String remoteFS,
            ComputerLauncher launcher,
            RetentionStrategy<AbstractCloudComputer> strategy,
            List<? extends NodeProperty<?>> nodeProperties) throws FormException, IOException {
        //CS IGNORE check FOR NEXT 4 LINES. REASON: necessary inline conditional in super().
        super(id.getNodeName(), "", remoteFS, NUM_EXECUTORS,
                label == null ? Node.Mode.NORMAL : Node.Mode.EXCLUSIVE,
                label, launcher, strategy, nodeProperties);
        LOGGER.info("Instancing a new ForemanSharedNode: name='" + name + "', label='"
                + (label == null ? "<NULL>" : label.toString()) + "'");
        this.id = id;
        this.cloudName = id.getCloudName();
    }

    @Override
    public AbstractCloudComputer<?> createComputer() {
        return new ForemanComputer(this);
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
        LOGGER.info("Terminating the ForemanSharedNode: name='" + name + "'");
        ForemanSharedNodeCloud.addDisposableEvent(cloudName, name);
    }

    @CheckForNull
    public String getCloudName() {
        return cloudName;
    }

    @Override
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
        public String getDisplayName() {
            return "Foreman Shared Node";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}

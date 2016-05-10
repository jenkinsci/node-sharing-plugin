package com.redhat.foreman;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.model.Queue.BuildableItem;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;
import java.util.List;

/**
 * Foreman Shared Node.
 *
 */
public class ForemanSharedNode extends AbstractCloudSlave {
    private static final int NUM_EXECUTORS = 1;

    private static final long serialVersionUID = -3284884519464420953L;

    private transient String cloudName;

    /**
     * Foreman Shared Node.
     * @param cloudName name of cloud.
     * @param name name or IP of host.
     * @param description same.
     * @param label Jenkins label requested.
     * @param remoteFS Remote FS root.
     * @param launcher Slave launcher.
     * @param strategy Retention Strategy.
     * @param nodeProperties node props.
     * @throws FormException if occurs.
     * @throws IOException if occurs.
     */
    public ForemanSharedNode(
            String cloudName,
            String name,
            String description,
            String label,
            String remoteFS,
            ComputerLauncher launcher,
            RetentionStrategy<AbstractCloudComputer> strategy,
            List<? extends NodeProperty<?>> nodeProperties) throws FormException, IOException {
        super(name, description, remoteFS, NUM_EXECUTORS,
                Node.Mode.EXCLUSIVE, label, launcher, strategy, nodeProperties);
        this.cloudName = cloudName;
    }

    @Override
    public void terminate() throws InterruptedException, IOException {
        ForemanSharedNodeCloud cloud = ForemanSharedNodeCloud.getByName(cloudName);
        cloud.getForemanAPI().release(name);
        super.terminate();
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
    //CS IGNORE MethodName FOR NEXT 2 LINES. REASON: Parent.
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        ForemanSharedNodeCloud cloud = ForemanSharedNodeCloud.getByName(cloudName);
        cloud.getForemanAPI().release(name);
    }

    /**
     * Slave descriptor.
     *
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

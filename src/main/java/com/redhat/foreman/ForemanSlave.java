package com.redhat.foreman;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Foreman Slave.
 *
 */
public class ForemanSlave extends AbstractCloudSlave {
    private static final int NUM_EXECUTORS = 1;

    private static final long serialVersionUID = -3284884519464420953L;

    private transient String cloudName;
    private transient JsonNode host;

    /**
     * Foreman Slave.
     * @param cloudName name of cloud.
     * @param host json form of resource.
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
    public ForemanSlave(
            String cloudName,
            JsonNode host,
            String name,
            String description,
            String label,
            String remoteFS,
            ComputerLauncher launcher,
            RetentionStrategy<AbstractCloudComputer> strategy,
            List<? extends NodeProperty<?>> nodeProperties) throws FormException, IOException {
        super(host.get("name").asText(), description, remoteFS, NUM_EXECUTORS,
                Node.Mode.EXCLUSIVE, label, launcher, strategy, nodeProperties);
        this.cloudName = cloudName;
        this.host = host;
    }

    @Override
    public void terminate() throws InterruptedException, IOException {
        ForemanCloud cloud = ForemanCloud.getByName(cloudName);
        cloud.getForemanAPI().release(host.get("name").asText());
        super.terminate();
    }

    @Override
    public AbstractCloudComputer<?> createComputer() {
        return new ForemanComputer(this);
    }

    @Override
    //CS IGNORE MethodName FOR NEXT 2 LINES. REASON: Parent.
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        ForemanCloud cloud = ForemanCloud.getByName(cloudName);
        cloud.getForemanAPI().release(host.get("name").asText());
    }

    /**
     * Slave descriptor.
     *
     */
    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Foreman Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}

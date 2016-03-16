package com.redhat.foreman;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Computer;
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

public class ForemanSlave extends AbstractCloudSlave {
    private static final int NUM_EXECUTORS = 1;

    private static final long serialVersionUID = -3284884519464420953L;

    private String cloudName;
    private JsonNode host;

    public ForemanSlave(
            String cloudName,
            JsonNode host,
            String name,
            String description,
            String label,
            String remoteFS,
            ComputerLauncher launcher,
            RetentionStrategy<Computer> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties) throws FormException, IOException {
        super(host.get("name").asText(), description, remoteFS, NUM_EXECUTORS, Node.Mode.EXCLUSIVE, label, launcher, retentionStrategy, nodeProperties);
        this.cloudName = cloudName;
        this.host = host;
    }

    @Override
    public AbstractCloudComputer<?> createComputer() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        ForemanCloud cloud = ForemanCloud.getByName(cloudName);
        cloud.getForemanAPI().release(host.get("name").asText());
    }

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

package com.redhat.foreman;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

public class ForemanCloud extends Cloud {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForemanCloud.class);

    protected ForemanCloud(String name) {
        super(name);
    }

    @Override
    public boolean canProvision(Label label) {
        return ForemanAPI.hasResources(label.toString());
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        Collection<NodeProvisioner.PlannedNode> result = new ArrayList<NodeProvisioner.PlannedNode>();
        if (ForemanAPI.hasAvailableResources(label.toString())) {
            result.add(new NodeProvisioner.PlannedNode(
                    label.toString(),
                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        public Node call() throws Exception {
                            try {
                                return provision(label);
                            } catch (Exception ex) {
                                LOGGER.error("Error in provisioning label='{}'", label.toString(), ex);
                                throw ex;
                            }
                        }
                    }),
                    1));
        }
        return result;
    }

    private ForemanSlave provision(Label label) throws IOException, Descriptor.FormException, ExecutionException {
        LOGGER.info("Trying to provision Foreman slave for {}", label.toString());

        final JsonNode host = ForemanAPI.reserve(label.toString());
        if (host != null) {
            String name = host.get("name").asText();
            String description = host.get("name").asText();
            String remoteFS = "/";
            SSHLauncher launcher = null;
            RetentionStrategy<Computer> strategy = RetentionStrategy.NOOP;
            List<? extends NodeProperty<?>> properties = null;
            return new ForemanSlave(host, name, description, label.toString(), remoteFS, launcher, strategy, properties);
        }

        // Something has changed and there are now no resources available...
        throw new ExecutionException(new NoForemanResourceAvailableException());
    }

    public static void main(String[] args) {
        ForemanCloud s = new ForemanCloud("foo");
        s.canProvision(new LabelAtom("label3"));
    }
}

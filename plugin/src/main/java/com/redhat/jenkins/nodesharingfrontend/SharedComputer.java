package com.redhat.jenkins.nodesharingfrontend;

import java.io.IOException;

import java.util.logging.Logger;

import hudson.remoting.VirtualChannel;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.AbstractCloudComputer;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.http.HttpServletResponse;

/**
 * Node Sharing Computer.
 */
public class SharedComputer extends AbstractCloudComputer<SharedNode> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(SharedComputer.class.getName());

    private final ProvisioningActivity.Id id;

    @Override
    public SharedNode getNode() {
        return super.getNode();
    }

    /**
     * Utility method to terminate a shared node.
     *
     * @param c The {@link Computer}
     * @throws IOException if occurs.
     * @throws InterruptedException if occurs.
     */
    public static void terminateComputer(Computer c) throws IOException, InterruptedException {
        if (c instanceof SharedComputer) {
            SharedComputer fc = (SharedComputer) c;
            Node node = fc.getNode();
            if (node != null) {
                SharedNode sharedNode = (SharedNode) node;

                VirtualChannel channel = sharedNode.getChannel();
                if (channel != null) {
                    channel.close();
                }

                sharedNode.terminate();
                LOGGER.info("Deleted slave " + node.getDisplayName());
            }
        }
    }

    /**
     * Delete the slave, terminate the instance
     *
     * @throws IOException if occurs
     * @throws InterruptedException if occurs
     */
    public void deleteSlave() throws IOException, InterruptedException {
        terminateComputer(this);
    }

    public SharedComputer(SharedNode slave) {
        super(slave);
        id = slave.getId();
    }

    @Override
    public ProvisioningActivity.Id getId() {
        return id;
    }

    // Hide /configure view inherited from Computer
    @Restricted(DoNotUse.class)
    public void doConfigure(StaplerResponse rsp) throws IOException {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    @Restricted(NoExternalUse.class)
    public HttpResponse doDoDelete() throws IOException {
        SharedNode node = getNode();
        if (node == null) {
            super.doDoDelete();
        }
        try {
            terminateComputer(this);
        } catch (Exception e) {
        }
        return new HttpRedirect("..");
    }
}

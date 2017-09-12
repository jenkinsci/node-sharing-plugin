package com.redhat.foreman;

import java.io.IOException;

import java.util.logging.Level;
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
 * Foreman Cloud Computer.
 */
public class ForemanComputer extends AbstractCloudComputer<ForemanSharedNode> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(ForemanComputer.class.getName());

    private final ProvisioningActivity.Id id;

    @Override
    public ForemanSharedNode getNode() {
        return super.getNode();
    }

    /**
     * Utility method to terminate a Foreman shared node.
     *
     * @param c The {@link Computer}
     * @throws IOException if occurs.
     * @throws InterruptedException if occurs.
     */
    public static void terminateForemanComputer(Computer c) throws IOException, InterruptedException {
        if (c instanceof ForemanComputer) {
            ForemanComputer fc = (ForemanComputer) c;
            Node node = fc.getNode();
            if (node != null) {
                ForemanSharedNode sharedNode = (ForemanSharedNode) node;

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
        terminateForemanComputer(this);
    }

    /**
     * Default constructor.
     *
     * @param slave Foreman slave {@link ForemanSharedNode}.
     */
    public ForemanComputer(ForemanSharedNode slave) {
        super(slave);
        LOGGER.fine("Instancing a new ForemanComputer: name='" + slave.getNodeName() + "'");
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
        ForemanSharedNode node = getNode();
        if (node == null) {
            super.doDoDelete();
        }
        try {
            terminateForemanComputer(this);
        } catch (Exception e) {
        }
        return new HttpRedirect("..");
    }
}

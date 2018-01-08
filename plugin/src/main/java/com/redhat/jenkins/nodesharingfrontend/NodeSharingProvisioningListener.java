package com.redhat.jenkins.nodesharingfrontend;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by pjanouse on 5/2/17.
 */
@Extension
public class NodeSharingProvisioningListener extends CloudProvisioningListener {
    private static final Logger LOGGER = Logger.getLogger(NodeSharingProvisioningListener.class.getName());

    @Override
    public CauseOfBlockage canProvision(Cloud c, Label l, int n) {
        LOGGER.info("NodeSharingProvisioningListener.canProvision() called for '"
                + l + ", workload: " + n);
        try {
            super.canProvision(c, l, n);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.canProvision(): ", e);
        }
        return null;
    }

    @Override
    public void onStarted(Cloud c, Label l, Collection<NodeProvisioner.PlannedNode> n) {
        LOGGER.info("NodeSharingProvisioningListener.onStarted() called for '"
                + l + ", size: " + n.size());
        try {
            super.onStarted(c, l, n);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.onStarted(): ", e);
        }
    }

    @Override
    public void onComplete(NodeProvisioner.PlannedNode pn, Node n) {
        LOGGER.info("NodeSharingProvisioningListener.onCompleted() called for '"
                + pn.displayName + "'; '" + n.getDisplayName() + "'");
        try {
            super.onComplete(pn, n);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.onComplete(): ", e);
        }
    }

    @Override
    public void onFailure(NodeProvisioner.PlannedNode pn, Throwable t) {
        LOGGER.info("NodeSharingProvisioningListener.onFailure() called for '"
                + pn.displayName + "'; '" + t.getMessage() + "'");
        try {
            super.onFailure(pn, t);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.onFailure(): ", e);
        }
    }
}

package com.redhat.jenkins.nodesharingfrontend;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/**
 * Computer listener to cleanup after failed launches.
 */
@Extension
public class NodeSharingComputerListener extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(NodeSharingComputerListener.class.getName());

    @Override
    public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        try {
            super.onLaunchFailure(c, taskListener);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.onLaunchFailed(): ", e);
        }
        if (c instanceof SharedComputer) {
            SharedComputer fc = (SharedComputer) c;
            ProvisioningActivity activity = CloudStatistics.get().getActivityFor(fc.getId());
            if (activity != null) {
                PhaseExecutionAttachment attachment = new PhaseExecutionAttachment(
                        ProvisioningActivity.Status.FAIL, "Launch failed with:\n" + c.getLog()
                );
                CloudStatistics.get().attach(activity, activity.getCurrentPhase(), attachment);
            }
            LOGGER.info("Launch of the Computer '" + c.getDisplayName() + "' failed, releasing...:\n" + c.getLog());
            SharedComputer.terminateComputer(c);
        }
    }

    @Override
    public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        try {
            super.preLaunch(c, taskListener);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.onLaunchFailed(): ", e);
        }
        if (c instanceof SharedComputer) {
            Node node = c.getNode();
            if (node instanceof SharedNode) {
               SharedNodeCloud cloud =
                       SharedNodeCloud.getByName(((SharedNode) node).getId().getCloudName());
               if (cloud == null || !cloud.isOperational()) {
                   // TODO these should never be saved (EphemeralNode) - do we still need this?
                   // PJ: We should do that because here is the last chance where we can drop the slave (otherwise it can exists forever)
                   throw new AbortException("This is a leaked SharedNode after Jenkins restart!");
               }
           }
        }
    }

    @Override
    public void onTemporarilyOnline(Computer c) {
        try {
            super.onTemporarilyOnline(c);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.onTemporarilyOnline(): ", e);
        }
        if (c instanceof SharedComputer) {
            if (c.isIdle()) {
                try {
                    SharedComputer.terminateComputer(c);
                } catch (InterruptedException | IOException e) {
                    LOGGER.log(Level.WARNING, "Uncaught unexpected exception occurred while terminaitng computer", e);
                }
            }
        }
    }
}

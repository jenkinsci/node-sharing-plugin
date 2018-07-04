package com.redhat.jenkins.nodesharingfrontend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

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
                        ProvisioningActivity.Status.FAIL, "Launch failed with:\n" + getLogText(c)
                );
                CloudStatistics.get().attach(activity, activity.getCurrentPhase(), attachment);
            }
            LOGGER.info("Launch of the Computer '" + c.getDisplayName() + "' failed, releasing...:\n" + c.getLog());
            SharedComputer.terminateComputer(c);
        }
    }

    // Stripping off the decoration that is not rendered anyway
    private String getLogText(Computer c) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.getLogText().writeLogTo(0, baos);
        return baos.toString();
    }

    @Override
    public void preLaunch(Computer c, TaskListener taskListener) throws IOException {
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
                   // Be defensive and prevent any slave that got serialized to launch returning it eagerly
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
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.WARNING, "Uncaught unexpected exception occurred while terminating computer", e);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Uncaught unexpected exception occurred while terminating computer", e);
                }
            }
        }
    }
}

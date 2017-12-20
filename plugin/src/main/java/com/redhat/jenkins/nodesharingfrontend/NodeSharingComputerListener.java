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
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        try {
            super.onLaunchFailure(c, taskListener);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Uncaught unexpected exception occurred while calling super.onLaunchFailed(): ", e);
        }
        if (c instanceof SharedComputer) {
            SharedComputer fc = (SharedComputer) c;
            CloudStatistics.get().attach(
                    CloudStatistics.get().getActivityFor(fc.getId()),
                    CloudStatistics.get().getActivityFor(fc.getId()).getCurrentPhase(),
                    new PhaseExecutionAttachment(ProvisioningActivity.Status.FAIL,
                            "Launch failed with:\n" + c.getLog()));
            LOGGER.info("Launch of the Computer '" + c.getDisplayName() + "' failed, releasing...:\n" + c.getLog());
            ((SharedComputer) c).terminateComputer(c);
        }
    }

    @Override
    @SuppressFBWarnings(value = "BC_VACUOUS_INSTANCEOF")
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
                       SharedNodeCloud.getByName(((SharedNode) node).getCloudName());
               if (cloud == null || !cloud.isOperational()) {
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
                    ((SharedComputer) c).terminateComputer(c);
                } catch (InterruptedException e) {
                } catch (IOException e) {
                }
            }
        }
    }
}

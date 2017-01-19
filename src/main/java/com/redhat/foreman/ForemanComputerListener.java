package com.redhat.foreman;

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

/**
 * Computer listener to cleanup after failed launches.
 */
@Extension
public class ForemanComputerListener extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(ForemanComputerListener.class.getName());

    @Override
    public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        try {
            super.onLaunchFailure(c, taskListener);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Uncaught unexpected exception occurred while calling super.onLaunchFailed(): ", e);
        }
        if (c instanceof ForemanComputer) {
            LOGGER.info("Launch of the Computer '" + c.getDisplayName() + "' failed, releasing...");
            ((ForemanComputer) c).eagerlyReturnNodeLater();
        }
    }

    @Override
    @SuppressFBWarnings(value = "BC_VACUOUS_INSTANCEOF")
    public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        try {
            super.preLaunch(c, taskListener);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Uncaught unexpected exception occurred while calling super.onLaunchFailed(): ", e);
        }
        if (c instanceof ForemanComputer) {
            Node node = c.getNode();
            if (node instanceof ForemanSharedNode) {
               ForemanSharedNodeCloud foremanCloud =
                       ForemanSharedNodeCloud.getByName(((ForemanSharedNode) node).getCloudName());
               if (foremanCloud == null || !foremanCloud.isOperational()) {
                   throw new AbortException("This is a leaked ForemanSharedNode after Jenkins restart!");
               }
           }
        }
    }

}

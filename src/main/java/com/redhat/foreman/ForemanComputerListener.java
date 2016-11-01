package com.redhat.foreman;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.Extension;
import hudson.model.Computer;
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

}

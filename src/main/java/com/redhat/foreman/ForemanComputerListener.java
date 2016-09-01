package com.redhat.foreman;

import java.io.IOException;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;

/**
 * Computer listener to cleanup after failed launches.
 */
@Extension
public class ForemanComputerListener extends ComputerListener {

    @Override
    public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        super.onLaunchFailure(c, taskListener);
        ForemanComputer.terminateForemanComputer(c);
    }

}

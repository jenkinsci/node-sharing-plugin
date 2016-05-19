package com.redhat.foreman;

import java.io.IOException;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;

/**
 * Computer listener to cleanup after failed launches.
 *
 */
@Extension
public class ForemanComputerListener extends ComputerListener {

    @Override
    public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
        super.onLaunchFailure(c, taskListener);
        if (c instanceof ForemanComputer) {
            ForemanComputer fc = (ForemanComputer)c;
            Node node = fc.getNode();
            if (node instanceof ForemanSharedNode) {
                ForemanSharedNode sharedNode = (ForemanSharedNode)node;
                sharedNode.terminate();
            }
        }
    }

}

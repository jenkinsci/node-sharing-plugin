package com.redhat.foreman;

import hudson.slaves.AbstractCloudComputer;

/**
 * Foreman Cloud Computer.
 */
public class ForemanComputer extends AbstractCloudComputer<ForemanSlave> {

    /**
     * Default constructor.
     * @param slave Foreman slave.
     */
    public ForemanComputer(ForemanSlave slave) {
        super(slave);
    }
}

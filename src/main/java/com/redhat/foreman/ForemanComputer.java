package com.redhat.foreman;

import hudson.slaves.AbstractCloudComputer;

public class ForemanComputer extends AbstractCloudComputer<ForemanSlave> {

    public ForemanComputer(ForemanSlave slave) {
        super(slave);
    }
}
package com.redhat.foreman;

import hudson.slaves.RetentionStrategy;

public class ForemanRetentionStrategy extends RetentionStrategy<ForemanComputer> {

    @Override
    public long check(ForemanComputer computer) {
        return 0;
    }
}
package com.redhat.foreman;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

/**
 * Check if node are online. If not, terminate.
 */
@Extension
public class ForemanSharedNodeMonitor extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(ForemanSharedNodeMonitor.class.getName());

    private final Long recurrencePeriod;

    /**
     * Default Constructor.
     */
    public ForemanSharedNodeMonitor() {
        super("Foreman Shared Node Alive Monitor");
        //CS IGNORE MagicNumber FOR NEXT 2 LINES. REASON: Parent.
        recurrencePeriod = Long.getLong("jenkins.foremansharenode.checkAlivePeriod", TimeUnit.MINUTES.toMillis(10));
        LOGGER.log(Level.FINE, "Foreman Shared Node check alive period is {0}ms", recurrencePeriod);
    }

    @Override
    public long getRecurrencePeriod() {
        return recurrencePeriod;
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        for (Computer cmp : Jenkins.getInstance().getComputers()) {
            if (cmp instanceof ForemanComputer) {
                if (cmp.isOffline()) {
                    LOGGER.info("Foreman Shared instance is dead: " + cmp.getDisplayName());
                    final ForemanSharedNode foremanNode = (ForemanSharedNode)cmp.getNode();
                    foremanNode._terminate(listener);
                }
            }
        }
    }
}

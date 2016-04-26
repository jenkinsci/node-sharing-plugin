package com.redhat.foreman;

import hudson.slaves.ComputerLauncher;

/**
 * Interface for Foreman Computer Launcher.
 *
 */
public abstract class ForemanComputerLauncherFactory {

    /**
     * Responsible for producing a Computer Launcher.
     * @return a ComputerLauncher
     * @throws Exception if occurs.
     */
    public abstract ComputerLauncher getForemanComputerLauncher() throws Exception;

}

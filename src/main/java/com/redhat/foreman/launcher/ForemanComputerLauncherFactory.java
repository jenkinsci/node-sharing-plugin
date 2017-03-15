package com.redhat.foreman.launcher;

import com.redhat.foreman.HostInfo;
import hudson.slaves.ComputerLauncher;

import javax.annotation.Nonnull;

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
    public abstract ComputerLauncher getForemanComputerLauncher(@Nonnull HostInfo host) throws Exception;

}

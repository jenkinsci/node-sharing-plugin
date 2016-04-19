package com.redhat.foreman;

import hudson.slaves.ComputerLauncher;

public abstract class ForemanComputerLauncherFactory {

    public abstract ComputerLauncher getForemanComputerLauncher() throws Exception;

}

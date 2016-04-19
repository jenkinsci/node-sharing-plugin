package com.redhat.foreman;

import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;

public class ForemanSSHComputerLauncherFactory extends ForemanComputerLauncherFactory {

    private String hostName;
    private int port;

    public ForemanSSHComputerLauncherFactory(String hostName, int port) {
        super();
        this.hostName = hostName;
        this.port = port;
    }

    public void configure(String host, int port) {
        this.hostName = host;
        this.port = port;
    }

    @Override
    public ComputerLauncher getForemanComputerLauncher() throws Exception {
        return new SSHLauncher(hostName,
                port,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

}

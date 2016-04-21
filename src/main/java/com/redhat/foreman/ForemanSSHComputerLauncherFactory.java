package com.redhat.foreman;

import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;

public class ForemanSSHComputerLauncherFactory extends ForemanComputerLauncherFactory {

    private String hostName;
    private int port;
    private String credentialsId;

    public ForemanSSHComputerLauncherFactory(String hostName, int port, String credentialsId) {
        super();
        this.hostName = hostName;
        this.port = port;
        this.credentialsId = credentialsId;
    }

    public void configure(String host, int port, String credentialsId) {
        this.hostName = host;
        this.port = port;
        this.credentialsId = credentialsId;
    }

    @Override
    public ComputerLauncher getForemanComputerLauncher() throws Exception {
        return new SSHLauncher(hostName,
                port,
                credentialsId,
                null,
                null,
                null,
                null);
    }

}

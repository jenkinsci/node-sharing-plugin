package com.redhat.foreman.launcher;

import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;

/**
 * SSH Launcher Factory.
 *
 */
public class ForemanSSHComputerLauncherFactory extends ForemanComputerLauncherFactory {

    private String hostName;
    private int port;
    private String credentialsId;

    /**
     * Default Constructor.
     * @param host name or ip of host to launch.
     * @param p port to launch on.
     * @param credId Credentials to use.
     */
    public ForemanSSHComputerLauncherFactory(String host, int p, String credId) {
        super();
        this.hostName = host;
        this.port = p;
        this.credentialsId = credId;
    }

    /**
     * Configure launcher once created.
     * @param host name or ip of host to launch.
     * @param p port to launch on.
     * @param credId Credentials to use.
     */
    public void configure(String host, int p, String credId) {
        this.hostName = host;
        this.port = p;
        this.credentialsId = credId;
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

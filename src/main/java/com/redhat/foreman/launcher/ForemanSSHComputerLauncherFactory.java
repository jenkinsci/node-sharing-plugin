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
    private Integer timeoutInSecs;

    /**
     * Default Constructor.
     * @param host name or ip of host to launch.
     * @param p port to launch on.
     * @param credId Credentials to use.
     * @param timeOut timeout for SSH connection in secs.
     */
    public ForemanSSHComputerLauncherFactory(String host, int p, String credId, Integer timeOut) {
        super();
        this.hostName = host;
        this.port = p;
        this.credentialsId = credId;
        this.timeoutInSecs = timeOut;
    }

    /**
     * Configure launcher once created.
     * @param host name or ip of host to launch.
     * @param p port to launch on.
     * @param credId Credentials to use.
     * @param timeOut timeout for SSH connection in secs.
     */
    public void configure(String host, int p, String credId, Integer timeOut) {
        this.hostName = host;
        this.port = p;
        this.credentialsId = credId;
        this.timeoutInSecs = timeOut;
    }

    @Override
    public ComputerLauncher getForemanComputerLauncher() throws Exception {
        return new SSHLauncher(hostName,
                port,
                credentialsId,
                null,
                null,
                null,
                null,
                timeoutInSecs);
    }

}

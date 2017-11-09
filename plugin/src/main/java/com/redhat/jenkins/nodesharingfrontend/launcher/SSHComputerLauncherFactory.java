package com.redhat.jenkins.nodesharingfrontend.launcher;

import com.redhat.jenkins.nodesharingfrontend.HostInfo;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;

import javax.annotation.Nonnull;

/**
 * SSH Launcher Factory.
 */
public class ForemanSSHComputerLauncherFactory extends SharedNodeComputerLauncherFactory {

    private final int port;
    private final String credentialsId;
    private final Integer timeoutInSecs;

    /**
     * Populate factory with host agnostic configuration.
     *
     * @param p port to launch on.
     * @param credId Credentials to use.
     * @param timeOut timeout for SSH connection in secs.
     */
    public ForemanSSHComputerLauncherFactory(int p, String credId, Integer timeOut) {
        super();
        this.port = p;
        this.credentialsId = credId;
        this.timeoutInSecs = timeOut;
    }

    /**
     * Create launcher tailored for particular host.
     */
    @Override
    public ComputerLauncher getForemanComputerLauncher(@Nonnull HostInfo host) {
        return new SSHLauncher(host.getName(),
                port,
                credentialsId,
                null,
                host.getJavaPath(),
                null,
                null,
                timeoutInSecs);
    }
}

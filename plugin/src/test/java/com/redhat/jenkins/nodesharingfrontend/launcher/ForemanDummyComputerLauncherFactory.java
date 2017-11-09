package com.redhat.jenkins.nodesharingfrontend.launcher;

import java.io.File;

import com.redhat.jenkins.nodesharingfrontend.HostInfo;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;

/**
 * Dummy Foreman Launcher for unit testing.
 *
 */
public class ForemanDummyComputerLauncherFactory extends SharedNodeComputerLauncherFactory {

    @Override
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public ComputerLauncher getComputerLauncher(@Nonnull HostInfo host) throws Exception {
        //Taken from JenkinsRule
        String slaveJar = new File(Jenkins.getInstance().getJnlpJars("slave.jar").getURL().toURI()).getAbsolutePath();
        String javaPath = host.getJavaPath();
        if (javaPath == null) {
            javaPath = System.getProperty("java.home") + "/bin/java";
        }
        String command = String.format("'%s' %s -jar '%s'", javaPath, "", slaveJar);
        return new CommandLauncher(command, new EnvVars());
    }
}

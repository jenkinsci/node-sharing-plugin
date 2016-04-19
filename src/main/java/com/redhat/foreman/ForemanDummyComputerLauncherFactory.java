package com.redhat.foreman;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;

import hudson.EnvVars;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import jenkins.model.Jenkins;

public class ForemanDummyComputerLauncherFactory extends ForemanComputerLauncherFactory {

    @Override
    public ComputerLauncher getForemanComputerLauncher() throws Exception {
        //Taken from JenkinsRule
        return new CommandLauncher(
                String.format("\"%s/bin/java\" %s -jar \"%s\"",
                        System.getProperty("java.home"),
                        "",
                        new File(Jenkins.getInstance().getJnlpJars("slave.jar").getURL().toURI()).getAbsolutePath()),
                new EnvVars());    }

}

package com.redhat.foreman.cli;

import com.beust.jcommander.Parameter;
import com.redhat.foreman.cli.exception.ForemanApiException;

/**
 * Created by shebert on 10/01/17.
 */
public abstract class Command {

    @Parameter(names = "--server", required = true,
            description = "URL of Foreman server. Must end in /v2")
    protected String server;

    @Parameter(names = "--user", required = true,
            description = "User to connect to Foreman")
    protected String user;

    @Parameter(names = "--password", required = true,
            description = "Password to connect to Foreman")
    protected String password;

    public abstract void run() throws ForemanApiException;
}

package com.scoheb.foreman.cli;

import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.scoheb.foreman.cli.model.Environment;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hostgroup;
import com.scoheb.foreman.cli.model.Parameter;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

@Parameters(separators = "=", commandDescription = "List hosts in Foreman")
public class ListHosts extends Command {

    private static Logger LOGGER = Logger.getLogger(List.class);

    @com.beust.jcommander.Parameter(names = "--hostgroup",
            description = "Search by hostgroup")
    public String hostGroup = null;

    @com.beust.jcommander.Parameter(names = "--environment",
            description = "Search by environment")
    public String environment = null;

    @Override
    public void run() {
        if (hostGroup != null && environment != null) {
            throw new ParameterException("Both environment and hostgroup cannot be set at same time");
        }
        Api api = new Api(server, user, password);
        List<Host> hosts = new ArrayList<Host>();
        if (hostGroup == null && environment == null) {
            LOGGER.info("Listing ALL hosts:");
            hosts = api.getHosts();
        } else {
            if (hostGroup != null) {
                LOGGER.info("Listing hosts in hostgroup " + hostGroup + ":");
                Hostgroup hostGroupObj = api.getHostGroup(hostGroup);
                if (hostGroupObj == null) {
                    throw new RuntimeException("Hostgroup " + hostGroup + " not found");
                }
                hosts = api.getHosts(hostGroupObj);
            }
            if (environment != null) {
                LOGGER.info("Listing hosts in environment " + environment + ":");
                Environment environmentObj = api.getEnvironment(environment);
                if (environmentObj == null) {
                    throw new RuntimeException("Environment " + environment + " not found");
                }
                hosts = api.getHosts(environmentObj);
            }
        }
        LOGGER.info("Found " + hosts.size() + " host(s).");
        for (Host h: hosts) {
            Host h2 = api.getHost(h.name);
            LOGGER.info(h2.name);
            for (Parameter param: h2.parameters) {
                LOGGER.info("--> " + param.name + ": " + Api.fixValue(param));
            }
        }
    }
}

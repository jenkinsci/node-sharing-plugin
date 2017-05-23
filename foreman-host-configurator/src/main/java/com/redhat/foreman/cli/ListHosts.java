package com.redhat.foreman.cli;

import com.beust.jcommander.Parameters;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Parameter;
import org.apache.log4j.Logger;

import java.util.List;

@Parameters(separators = "=", commandDescription = "List hosts in Foreman")
public class ListHosts extends Command {

    private static Logger LOGGER = Logger.getLogger(List.class);

    @com.beust.jcommander.Parameter(names = "--query",
            description = "Search using a query. You must use \" when " +
                    "specifying a value with a space. For example: hostgroup = \"staging\"")
    public String query = null;

    @Override
    public void run() {
        Api api = new Api(server, user, password);
        List<Host> hosts;
        if (query == null) {
            LOGGER.info("Listing ALL hosts:");
            hosts = api.getHosts();
        } else {
            LOGGER.info("Listing hosts using query: " + query + ":");
            hosts = api.getHosts(query);
        }
        LOGGER.info("Found " + hosts.size() + " host(s).");
        for (Host h: hosts) {
            Host h2 = api.getHost(h.getName());
            LOGGER.info(h2.getName());
            for (Parameter param: h2.parameters) {
                LOGGER.info("--> " + param.getName() + ": " + Api.fixValue(param));
            }
        }
    }
}

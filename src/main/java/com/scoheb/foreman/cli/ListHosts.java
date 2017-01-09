package com.scoheb.foreman.cli;

import com.scoheb.foreman.cli.model.Environment;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hostgroup;
import com.scoheb.foreman.cli.model.Parameter;
import org.apache.log4j.Logger;
import java.util.List;

public class ListHosts {

    private static Logger LOGGER = Logger.getLogger(List.class);

    public static void main(String[] args) {
        String user = "admin";
        String password = "changeme";

        Api api = new Api("http://localhost:3000/api/v2/", user, password);
        Environment environment = api.getEnvironment("staging");
        List<Host> hosts = api.getHosts(environment);
        for (Host h: hosts) {
            LOGGER.info(h.name);
            Parameter param = api.getHostParameter(h, "RESERVED");
            LOGGER.info("--> RESERVED: " + Api.fixValue(param));
            param = api.getHostParameter(h, "JENKINS_LABEL");
            LOGGER.info("--> JENKINS_LABEL: " + Api.fixValue(param));
            param = api.getHostParameter(h, "JENKINS_SLAVE_REMOTEFS_ROOT");
            LOGGER.info("--> JENKINS_SLAVE_REMOTEFS_ROOT: " + Api.fixValue(param));
        }
        Hostgroup hostGroup = api.getHostGroup("staging servers");
        hosts = api.getHosts(hostGroup);
        for (Host h: hosts) {
            LOGGER.info(h);
        }
    }
}

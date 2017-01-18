package com.scoheb.foreman.cli;

import com.beust.jcommander.Parameters;
import com.scoheb.foreman.cli.exception.ForemanApiException;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hosts;
import com.scoheb.foreman.cli.model.Parameter;
import org.apache.log4j.Logger;

import java.util.List;

@Parameters(separators = "=", commandDescription = "Update Hosts in Foreman from file")
public class UpdateFromFile extends AbstractFileProcessor {

    private static Logger LOGGER = Logger.getLogger(UpdateFromFile.class);

    public UpdateFromFile(List<String> files) {
        this.files = files;
    }

    public UpdateFromFile() {
    }

    @Override
    public void perform(Hosts hosts) throws ForemanApiException {
        Api api = new Api(server, user, password);
        for (Host host: hosts.getHosts()) {
            checkHostAttributes(host);
            LOGGER.info("Updating " + host.name + "." + host.domain_name);
            Host hostObj = api.getHost(host.name + "." + host.domain_name);
            if (hostObj == null) {
                throw new RuntimeException("Host " + host.name + "."+ host.domain_name + " DOES NOT EXIST");
            }
            if (host.parameters != null && host.parameters.size() > 0) {
                for (Parameter p: host.parameters) {
                    if (p.name.equals("RESERVED")) {
                        LOGGER.warn("The parameter RESERVED cannot be updated via this commmand." +
                                " You must use the 'release' command.");
                        continue;
                    }
                    api.updateHostParameter(hostObj, p);
                    LOGGER.info("Added/Updated parameter " + p.name);
                }
            }
        }
    }

}

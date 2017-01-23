package com.scoheb.foreman.cli;

import com.beust.jcommander.Parameters;
import com.scoheb.foreman.cli.exception.ForemanApiException;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hosts;
import com.scoheb.foreman.cli.model.Parameter;
import org.apache.log4j.Logger;

import java.util.List;

@Parameters(separators = "=", commandDescription = "Create Hosts in Foreman from file")
public class CreateFromFile extends AbstractFileProcessor {

    private static Logger LOGGER = Logger.getLogger(CreateFromFile.class);

    public CreateFromFile(List<String> files) {
        this.files = files;
    }

    public CreateFromFile() {
    }

    @Override
    public void perform(Hosts hosts) throws ForemanApiException {
        Api api = new Api(server, user, password);
        for (Host host: hosts.getHosts()) {
            checkHostAttributes(host);
            Host hostObj = api.getHost(host.name);
            if (hostObj != null) {
                throw new RuntimeException("Host " + host.name + " already exists");
            }
            hostObj = api.createHost(host.name);
            if (hostObj == null) {
                throw new RuntimeException("Failed to create Host " + host.name);
            }
            LOGGER.info("Created " + hostObj.name);
            //
            if (hosts.getDefaults().parameters != null && hosts.getDefaults().parameters.size() > 0) {
                for (Parameter p: hosts.getDefaults().parameters) {
                    api.updateHostParameter(hostObj, p);
                    LOGGER.info("Added/Updated Default parameter " + p.name);
                }
            }
            if (host.parameters != null && host.parameters.size() > 0) {
                for (Parameter p: host.parameters) {
                    api.updateHostParameter(hostObj, p);
                    LOGGER.info("Added/Updated parameter " + p.name);
                }
            }
        }
    }

}

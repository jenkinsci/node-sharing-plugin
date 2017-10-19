package com.redhat.foreman.cli;

import com.beust.jcommander.Parameters;
import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Hosts;
import com.redhat.foreman.cli.model.Parameter;
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
            Host hostObj = api.getHost(host.getName());
            if (hostObj != null) {
                throw new RuntimeException("Host " + host.getName() + " already exists");
            }
            hostObj = api.createHost(host.getName());
            if (hostObj == null) {
                throw new RuntimeException("Failed to create Host " + host.getName());
            }
            LOGGER.info("Created " + hostObj.getName());
            //
            if (hosts.getDefaults().getParameters().size() > 0) {
                for (Parameter p: hosts.getDefaults().getParameters()) {
                    api.updateHostParameter(hostObj, p);
                    LOGGER.info("Added/Updated Default parameter " + p.getName());
                }
            }
            if (host.getParameters().size() > 0) {
                for (Parameter p: host.getParameters()) {
                    api.updateHostParameter(hostObj, p);
                    LOGGER.info("Added/Updated parameter " + p.getName());
                }
            }
        }
    }

}

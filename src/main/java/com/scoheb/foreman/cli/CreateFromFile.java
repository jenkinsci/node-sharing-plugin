package com.scoheb.foreman.cli;

import com.beust.jcommander.Parameters;
import com.scoheb.foreman.cli.model.Domain;
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
    public void perform(Hosts hosts) {
        Api api = new Api(server, user, password);
        for (Host host: hosts.hosts) {
            checkHostAttributes(host);
            Domain domain = api.createDomain(host.domain_name);
            if (domain == null) {
                throw new RuntimeException("Failed to create Domain " + host.domain_name);
            }
            Host hostObj = api.createHost(host.name, host.ip_address,
                    domain);
            if (hostObj == null) {
                throw new RuntimeException("Failed to create Host " + host.name);
            }
            LOGGER.info("Created " + hostObj.name);
            if (host.parameters != null && host.parameters.size() > 0) {
                for (Parameter p: host.parameters) {
                    api.updateHostParameter(hostObj, p);
                    LOGGER.info("Added/Updated parameter " + p.name);
                }
            }
        }
    }

}

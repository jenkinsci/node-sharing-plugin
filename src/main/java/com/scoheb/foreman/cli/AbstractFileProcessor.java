package com.scoheb.foreman.cli;

import com.google.gson.Gson;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hosts;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Created by shebert on 17/01/17.
 */
public abstract class AbstractFileProcessor extends Command {

    @com.beust.jcommander.Parameter(description = "<Files to process>"
            , required = true)
    protected List<String> files;

    private static Logger LOGGER = Logger.getLogger(AbstractFileProcessor.class);

    public AbstractFileProcessor(List<String> files) {
        this.files = files;
    }

    protected AbstractFileProcessor() {
    }

    @Override
    public void run() {
        if (files == null) {
            throw new RuntimeException("No files provided");
        }
        for (String path: files) {
            File f = new File(path);
            if (!f.exists()) {
                throw new RuntimeException("File " + f.getAbsolutePath() + " does not exist.");
            }
            LOGGER.info("Processing " + f.getAbsolutePath());
            String json = null;
            try {
                json = FileUtils.readFileToString(f);
            } catch (IOException e) {
                throw new RuntimeException("Exception while trying to read File " + f.getAbsolutePath() + " - " + e.getMessage());
            }
            Gson gson = new Gson();
            Hosts hosts = gson.fromJson(json, Hosts.class);
            if (hosts == null || hosts.hosts == null || hosts.hosts.size() == 0) {
                throw new RuntimeException("No Hosts loaded from " + f.getAbsolutePath());
            }
            perform(hosts);
        }
    }

    public abstract void perform(Hosts hosts);

    protected void checkHostAttributes(Host host) {
        if (host.name == null || host.name.equals("")) {
            throw new RuntimeException("host is missing its 'name' attribute");
        }
        if (host.domain_name == null || host.domain_name.equals("")) {
            throw new RuntimeException("host is missing its 'domain_name' attribute");
        }
        if (host.ip_address == null || host.ip_address.equals("")) {
            throw new RuntimeException("host is missing its 'ip' attribute");
        }
    }
}

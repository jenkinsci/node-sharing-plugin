package com.redhat.foreman.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.HostTypeAdapter;
import com.redhat.foreman.cli.model.Hosts;
import com.redhat.foreman.cli.model.Parameter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;

/**
 * Created by shebert on 17/01/17.
 */
public abstract class AbstractFileProcessor extends Command {

    @com.beust.jcommander.Parameter(description = "<Files to process>"
            , required = true)
    protected List<String> files;

    @com.beust.jcommander.Parameter(names = "--properties",
            description = "Properties file whose key/value pairs can be used as tokens")
    protected String properties;

    @com.beust.jcommander.Parameter(names = "--csv",
            description = "Process file as a CSV with ';' delimiter")
    protected boolean csv;

    public void setCsv(boolean csv) { this.csv = csv; }

    private static Logger LOGGER = Logger.getLogger(AbstractFileProcessor.class);
    private final Properties props = new Properties();

    public AbstractFileProcessor(List<String> files) {
        this.files = files;
    }

    protected AbstractFileProcessor() {
    }

    @Override
    public void run() throws ForemanApiException {
        if (files == null) {
            throw new RuntimeException("No files provided");
        }
        if (properties != null) {
            FileInputStream propFileStr = null;
            try {
                File p = new File(properties);
                propFileStr = new FileInputStream(p);
                props.load(propFileStr);
                LOGGER.info("Loading properties file: " + p.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("Could load properties from " + properties + ". " + e.getMessage());
            } finally {
                if (propFileStr != null) {
                    try {
                        propFileStr.close();
                    } catch (IOException e) {
                        //swallow
                    }
                }
            }
        }
        for (String path: files) {
            File f = new File(path);
            if (!f.exists()) {
                throw new RuntimeException("File " + f.getAbsolutePath() + " does not exist.");
            }
            LOGGER.info("Processing " + f.getAbsolutePath());
            String json;
            try {
                String rawString = FileUtils.readFileToString(f);
                LOGGER.debug("raw file: " + rawString);
                StrSubstitutor sub = new StrSubstitutor(props);
                json = sub.replace(rawString);
                LOGGER.debug("substituted file: " + json);
            } catch (IOException e) {
                throw new RuntimeException("Exception while trying to read File " + f.getAbsolutePath() + " - " + e.getMessage());
            }
            Hosts hosts = new Hosts();
            if (csv) {
                try {
                    for (CSVRecord record :
                            CSVFormat.DEFAULT.withDelimiter(';').withAllowMissingColumnNames(true).parse(new StringReader(json))) {
                        Host host = new Host();
                        host.setName(record.get(0));
                        if(record.size() > 1 && record.get(1).length() > 0) {
                            host.addParameter(new Parameter(
                                    HostTypeAdapter.getParameterMapping().get("labels"), record.get(1)));
                        }
                        if(record.size() > 2 && record.get(2).length() > 0) {
                            host.addParameter(new Parameter(
                                    HostTypeAdapter.getParameterMapping().get("remoteFs"), record.get(2)));
                        }
                        if(record.size() > 3 && record.get(3).length() > 0) {
                            host.addParameter(new Parameter(
                                    HostTypeAdapter.getParameterMapping().get("javaPath"), record.get(3)));
                        }
                        hosts.getHosts().add(host);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException("Exception while trying to parse '" + json + "' - " + e.getMessage());
                }
            } else {
                final GsonBuilder gsonBuilder = new GsonBuilder();
                gsonBuilder.registerTypeAdapter(Host.class, new HostTypeAdapter());
                gsonBuilder.setPrettyPrinting();

                final Gson gson = gsonBuilder.create();
                hosts = gson.fromJson(json, Hosts.class);
            }
            if (hosts == null || hosts.getHosts() == null || hosts.getHosts().size() == 0) {
                throw new RuntimeException("No Hosts loaded from " + f.getAbsolutePath());
            }
            if (hosts.getParameterValue("RESERVED") == null) {
                hosts.addDefaultParameter(new Parameter("RESERVED", "false"));
            }
            perform(hosts);
        }
    }

    public abstract void perform(Hosts hosts) throws ForemanApiException;

    protected void checkHostAttributes(@Nonnull Host host) {
        String hostName = host.getName();
        if (hostName== null || hostName.length() == 0) {
            throw new RuntimeException("host is missing its 'name' attribute");
        }
        if (host.parameters != null) {
            for (Parameter p: host.parameters) {
                if (p.getName() == null || p.getName().equals("")) {
                    throw new RuntimeException("host parameter is missing its 'name' attribute: " + p.toString());
                }
                if (p.getValue() == null || p.getValue().equals("")) {
                    throw new RuntimeException("host parameter is missing its 'value' attribute: " + p.toString());
                }
            }
        }
    }
}

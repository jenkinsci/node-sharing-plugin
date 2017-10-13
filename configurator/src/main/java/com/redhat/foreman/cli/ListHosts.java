package com.redhat.foreman.cli;

import com.beust.jcommander.Parameters;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Parameter;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

@Parameters(separators = "=", commandDescription = "List hosts in Foreman")
public class ListHosts extends Command {

    private static Logger LOGGER = Logger.getLogger(List.class);

    @com.beust.jcommander.Parameter(names = "--query",
            description = "Search using a query. You must use \" when " +
                    "specifying a value with a space. For example: hostgroup = \"staging\"")
    public String query = null;

    @com.beust.jcommander.Parameter(names = "--csv",
            description = "Lists Hosts as a CSV")
    protected boolean csv;

    public void setCsv(boolean csv) { this.csv = csv; }

    @com.beust.jcommander.Parameter(names = "--file",
            description = "When using together with '--csv' the output will be sent to file")
    protected String fileName = null;

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
        BufferedWriter bw = null;
        Writer fw = null;
        try {
            if (fileName != null) {
                fw = new OutputStreamWriter(new FileOutputStream(fileName), "UTF-8");
                bw = new BufferedWriter(fw);
            }
            for (Host h : hosts) {
                Host h2 = api.getHost(h.getName());
                if (csv) {
                    String line = h2.getName()
                            + ";"
                            + (h2.getParameterValue("JENKINS_LABEL") == null ?
                            "" : h2.getParameterValue("JENKINS_LABEL").getValue())
                            + ";"
                            + (h2.getParameterValue("JENKINS_SLAVE_REMOTEFS_ROOT") == null ?
                            "" : h2.getParameterValue("JENKINS_SLAVE_REMOTEFS_ROOT").getValue())
                            + ";"
                            + (h2.getParameterValue("JENKINS_SLAVE_JAVA_PATH") == null ?
                            "" : h2.getParameterValue("JENKINS_SLAVE_JAVA_PATH").getValue());
                    if (bw != null) {
                        bw.write(line + "\n");
                    } else {
                        LOGGER.info(line);
                    }
                } else {
                    LOGGER.info(h2.getName());
                    for (Parameter param : h2.parameters) {
                        LOGGER.info("--> " + param.getName() + ": " + Api.fixValue(param));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (bw != null) {
                    bw.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                if (fw != null) {
                    fw.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

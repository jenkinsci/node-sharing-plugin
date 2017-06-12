package com.redhat.foreman.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.HostTypeAdapter;
import com.redhat.foreman.cli.model.Hosts;
import com.redhat.foreman.cli.model.Parameter;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertEquals;

/**
 * Created by shebert on 20/01/17.
 */
public class PerformanceTest extends AbstractTest {

    private String startList = "{\n" +
            "  \"hosts\": [\n" +
            "    {\n" +
            "      \"name\": \"scott.localdomain\",\n" +
            "      \"labels\": \"scott scott\",\n" +
            "      \"remoteFs\": \"/tmp/scott\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";
    private final String checkHostName = "scott-4.localdomain";
    

    @Test
    public void testManyHosts() throws ForemanApiException {
        String url = getUrl();

        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Host.class, new HostTypeAdapter());
        gsonBuilder.setPrettyPrinting();

        final Gson gson = gsonBuilder.create();
        final Hosts hosts = gson.fromJson(startList, Hosts.class);
        assertNotNull(hosts);
        Host firstHost = hosts.getHosts().get(0);
        assertNotNull(firstHost);
        assertNotNull(firstHost.parameters);
        assertTrue(firstHost.parameters.size() > 0);

        for(int i=1; i<5; i++){
            Host newHost = new Host();
            newHost.setName("scott-" + i + ".localdomain");
            newHost.parameters = firstHost.parameters;
            hosts.getHosts().add(newHost);
        }

        String hostsJson = gson.toJson(hosts);
        File configFile = null;
        try {
            configFile = File.createTempFile("perftest", ".json");
            FileUtils.writeStringToFile(configFile, hostsJson);
        } catch (IOException e) {
            fail(e.getCause().getMessage());
        }
        List<String> files = new ArrayList<>();
        files.add(configFile.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.run();

        Host checkHost = api.getHost(checkHostName);
        assertNotNull(checkHost);
        api.updateHostParameter(checkHost, new Parameter("JENKINS_LABEL", "SCOTT2 TOM2"));

        UpdateFromFile updater = new UpdateFromFile(files);
        updater.server = url;
        updater.user = user;
        updater.password = password;
        updater.run();

        checkHost = api.getHost(checkHostName);
        Parameter parameter = checkHost.getParameterValue("JENKINS_LABEL");
        Assert.assertNotNull(parameter);
        assertEquals("Should be scott scott", "scott scott", parameter.getValue());
    }
}

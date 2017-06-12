package com.redhat.foreman.cli;

import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Parameter;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by shebert on 17/01/17.
 */
public class UpdateTest extends AbstractTest {

    @Test
    public void testUpdate2Hosts() throws ForemanApiException {
        String url = getUrl();

        File createJson = getResourceAsFile("create.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.run();

        createJson = getResourceAsFile("create-parameters-updated.json");
        files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        UpdateFromFile updater = new UpdateFromFile(files);
        updater.server = url;
        updater.user = user;
        updater.password = password;
        updater.run();

        Host checkHost = api.getHost("scott.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        Parameter parameter = checkHost.getParameterValue("JENKINS_LABEL");
        assertNotNull(parameter);
        assertEquals("Should be SCOTT2 TOM2", "SCOTT2 TOM2", parameter.getValue());

    }

    @Test
    public void testUpdateReservedHost() throws ForemanApiException {
        String url = getUrl();

        File createJson = getResourceAsFile("release.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.run();

        File createJson2 = getResourceAsFile("release-with-update.json");
        List<String> files2 = new ArrayList<String>();
        files2.add(createJson2.getAbsolutePath());

        UpdateFromFile updater = new UpdateFromFile(files2);
        updater.server = url;
        updater.user = user;
        updater.password = password;
        updater.run();

        assertTrue(systemOutRule.getLog().contains("Host host-to-release.localdomain is reserved (Reserved by Scott :)). Will update..."));
        Host checkHost = api.getHost("host-to-release.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        Parameter parameter = checkHost.getParameterValue("RESERVED");
        assertNotNull(parameter);
        assertEquals("Should be 'Reserved by Scott :)'", "Reserved by Scott :)", parameter.getValue());

        List<String> hosts = new ArrayList<String>();
        hosts.add("host-to-release.localdomain");

        Release release = new Release(hosts);
        release.server = url;
        release.user = user;
        release.password = password;
        release.setForce(true);
        release.run();

        checkHost = api.getHost("host-to-release.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        parameter = checkHost.getParameterValue("RESERVED");
        assertNotNull(parameter);
        assertEquals("Should be 'false'", "false", parameter.getValue());

        updater.run();

        assertTrue(systemOutRule.getLog().contains("Added/Updated parameter JENKINS_LABEL to be 'ABC'"));
        checkHost = api.getHost("host-to-release.localdomain");
        parameter = checkHost.getParameterValue("JENKINS_LABEL");
        assertNotNull(parameter);
        assertEquals("Should be 'ABC'", "ABC", parameter.getValue());
    }
}

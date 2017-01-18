package com.scoheb.foreman.cli;

import com.scoheb.foreman.cli.exception.ForemanApiException;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Parameter;
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
public class ReleaseTest extends AbstractTest {

    @Test
    public void testRelease() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("release.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.run();

        Host checkHost = api.getHost("host-to-release.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        Parameter parameter = checkHost.getParameterValue("RESERVED");
        assertNotNull(parameter);
        assertEquals("Should be 'Reserved by Scott :)'", "Reserved by Scott :)", parameter.value);

        List<String> hosts = new ArrayList<String>();
        hosts.add("host-to-release.localdomain");

        Release release = new Release(hosts);
        release.server = url;
        release.user = user;
        release.password = password;
        release.setForce(false);
        release.run();

        checkHost = api.getHost("host-to-release.localdomain");
        parameter = checkHost.getParameterValue("RESERVED");
        assertEquals("Should be 'Reserved by Scott :)'", "Reserved by Scott :)", parameter.value);

        release = new Release(hosts);
        release.server = url;
        release.user = user;
        release.password = password;
        release.setForce(true);
        release.run();

        checkHost = api.getHost("host-to-release.localdomain");
        parameter = checkHost.getParameterValue("RESERVED");
        assertEquals("Should be 'false'", "false", parameter.value);

        release = new Release(hosts);
        release.server = url;
        release.user = user;
        release.password = password;
        release.setForce(true);
        release.run();
        assertTrue(systemOutRule.getLog()
                ,systemOutRule.getLog().indexOf("Host host-to-release.localdomain not reserved...") >= 0);
    }

    @Test
    public void testReleaseUnknownHost() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        List<String> hosts = new ArrayList<String>();
        hosts.add("unknownhost-to-release.localdomain");

        Release release = new Release(hosts);
        release.server = url;
        release.user = user;
        release.password = password;
        release.setForce(false);
        exception.expect(RuntimeException.class);
        release.run();
    }
}

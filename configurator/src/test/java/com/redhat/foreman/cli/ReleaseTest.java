package com.redhat.foreman.cli;

import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Parameter;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Created by shebert on 17/01/17.
 */
public class ReleaseTest extends AbstractTest {

    @Test
    public void testRelease() throws ForemanApiException {
        createFromFile("release.json");

        Host checkHost = api.getHost("host-to-release.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        Parameter parameter = checkHost.getParameterValue("RESERVED");
        assertNotNull(parameter);
        assertEquals("Should be 'Reserved by Scott :)'", "Reserved by Scott :)", parameter.getValue());

        releaseHosts("host-to-release.localdomain", false);

        checkHost = api.getHost("host-to-release.localdomain");
        parameter = checkHost.getParameterValue("RESERVED");
        assertEquals("Should be 'Reserved by Scott :)'", "Reserved by Scott :)", parameter.getValue());

        releaseHosts("host-to-release.localdomain", true);

        checkHost = api.getHost("host-to-release.localdomain");
        parameter = checkHost.getParameterValue("RESERVED");
        assertEquals("Should be 'false'", "false", parameter.getValue());

        releaseHosts("host-to-release.localdomain", true);
        assertThat(systemOutRule.getLog(), containsString("Host host-to-release.localdomain not reserved..."));
    }

    @Test
    public void testReleaseUnknownHost() throws ForemanApiException {
        exception.expect(RuntimeException.class);
        releaseHosts("unknownhost-to-release.localdomain", false);
    }
}

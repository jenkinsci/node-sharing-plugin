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
public class CreateTest extends AbstractTest {

    @Test
    public void testCreate2Hosts() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.run();

        Host checkHost = api.getHost("scott.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        Parameter parameter = checkHost.getParameterValue("JENKINS_LABEL");
        assertNotNull(parameter);
        assertEquals("Should be SCOTT TOM", "SCOTT TOM", parameter.getValue());
    }

    @Test
    public void testCreateWithTokens() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-with-tokens.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        File props = getResourceAsFile("tokens1.properties");

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.properties = props.getAbsolutePath();
        creator.run();

        Host checkHost = api.getHost("scott.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        Parameter parameter = checkHost.getParameterValue("JENKINS_SLAVE_REMOTE_FSROOT");
        assertNotNull(parameter);
        assertEquals("Should be /tmp/scott", "/tmp/scott", parameter.getValue());

        checkHost = api.getHost("scott2.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        parameter = checkHost.getParameterValue("JENKINS_SLAVE_REMOTE_FSROOT");
        assertNotNull(parameter);
        assertEquals("Should be /tmp/scott2", "/tmp/scott2", parameter.getValue());
    }

    @Test
    public void testMissingPropertiesFile() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-with-tokens.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.properties = "/tmp/ffgsfsfsd/sdfasfsaf/DOESNOTEXIST.properties";
        creator.run();

        Host checkHost = api.getHost("scott.localdomain");
        assertNotNull(checkHost);
        assertNotNull(checkHost.parameters);
        Parameter parameter = checkHost.getParameterValue("JENKINS_SLAVE_REMOTE_FSROOT");
        assertNotNull(parameter);
        assertEquals("Should be ${FSROOT}", "${FSROOT}", parameter.getValue());
        assertTrue(systemOutRule.getLog().indexOf("Could load properties from /tmp/ffgsfsfsd/sdfasfsaf/DOESNOTEXIST.properties") >= 0);
    }

    @Test
    public void testCreateAndAddParam() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        creator.run();

        Host checkHost = api.getHost("scott.localdomain");
        api.updateHostParameter(checkHost, new Parameter("SCOTT", "TOM"));
        Parameter p1 = api.getHostParameter(checkHost, "SCOTT");
        Host checkHost2 = api.getHost("scott.localdomain");
        Parameter p2 = checkHost2.getParameterValue("SCOTT");
        assertNotNull(p1);
        assertNotNull(p2);
        assertEquals("Should be TOM", p1.getValue(), p2.getValue());

        ListHosts listHosts = new ListHosts();
        listHosts.server = url;
        listHosts.user = user;
        listHosts.password = password;
        listHosts.run();
        assertTrue(systemOutRule.getLog().indexOf("Found 2 host") >= 0);

    }

    @Test
    public void testCreateDuplicateNames() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-same-name.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        exception.expect(RuntimeException.class);
        exception.expectMessage("Host scott.localdomain already exists");
        creator.run();
    }

    @Test
    public void testCreateMissingInfo() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-missing-name.json");
        ArrayList<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        exception.expect(RuntimeException.class);
        creator.run();

    }
}

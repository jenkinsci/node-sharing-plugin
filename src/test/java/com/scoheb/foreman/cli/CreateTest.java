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
        assertEquals("Should be SCOTT TOM", "SCOTT TOM", parameter.value);
    }

    //create-missing-param-value.json
    @Test
    public void testCreateWithMissingParamValue() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-missing-param-value.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        exception.expect(RuntimeException.class);
        exception.expectMessage("host parameter is missing its 'value' attribute: " +
                "Parameter{name='JENKINS_LABEL', value=null, id=0}");
        creator.run();

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
        assertEquals("Should be TOM", p1.value, p2.value);

        ListHosts listHosts = new ListHosts();
        listHosts.server = url;
        listHosts.user = user;
        listHosts.password = password;
        listHosts.run();
        assertTrue(systemOutRule.getLog().indexOf("Found 2 host") >= 0);

    }

    @Test
    public void testCreateDuplicateHosts() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-double.json");
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
    public void testCreateDuplicateIPs() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-same-ip.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        exception.expect(ForemanApiException.class);
        exception.expectMessage("Creating hosts returned code 422");
        creator.run();
    }

    @Test
    public void testCreateMissingInfo() throws ForemanApiException {
        String url = getUrl();
        waitUntilForemanReady(url);

        File createJson = getResourceAsFile("create-missing-domainname.json");
        List<String> files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        CreateFromFile creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        exception.expect(RuntimeException.class);
        creator.run();

        createJson = getResourceAsFile("create-missing-ip.json");
        files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        exception.expect(RuntimeException.class);
        creator.run();

        createJson = getResourceAsFile("create-missing-name.json");
        files = new ArrayList<String>();
        files.add(createJson.getAbsolutePath());

        creator = new CreateFromFile(files);
        creator.server = url;
        creator.user = user;
        creator.password = password;
        exception.expect(RuntimeException.class);
        creator.run();

    }
}

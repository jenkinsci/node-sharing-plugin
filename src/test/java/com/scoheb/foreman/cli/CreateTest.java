package com.scoheb.foreman.cli;

import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Parameter;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Created by shebert on 17/01/17.
 */
public class CreateTest extends AbstractTest {

    @Test
    public void testCreate2Hosts()  {
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

    @Test
    public void testCreateMissingInfo()  {
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

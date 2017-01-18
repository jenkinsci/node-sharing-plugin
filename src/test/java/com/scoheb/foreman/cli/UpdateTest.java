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

/**
 * Created by shebert on 17/01/17.
 */
public class UpdateTest extends AbstractTest {

    @Test
    public void testUpdate2Hosts() throws ForemanApiException {
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
        assertEquals("Should be SCOTT2 TOM2", "SCOTT2 TOM2", parameter.value);

    }
}

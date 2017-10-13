package com.redhat.foreman.cli;

import com.redhat.foreman.cli.docker.fixtures.ForemanContainer;
import com.redhat.foreman.cli.exception.ForemanApiException;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.jenkinsci.test.acceptance.docker.Resource;
import org.junit.Before;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;

import static junit.framework.TestCase.fail;

/**
 * Created by shebert on 17/01/17.
 */
public abstract class AbstractTest {
    @Rule
    public final ExpectedException exception = ExpectedException.none();
    @Rule
    public DockerRule<ForemanContainer> rule = new DockerRule<>(ForemanContainer.class);

    protected Api api;

    protected final String user = "admin";
    protected final String password = "changeme";

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog();

    @Before
    public void clearLog() {
        systemOutRule.clearLog();
    }

    protected File getResourceAsFile(String path) {
        URL resource = this.getClass().getResource(path);
        if(resource == null) {
            throw new AssertionError("No such resource " + path + " for " + this.getClass().getName());
        } else {
            Resource res = new Resource(resource);
            return res.asFile();
        }
    }

    public AbstractTest() {
    }

    protected String getUrl() {
        String url = null;
        try {
            url = rule.get().getUrl().toString() + "/api";
        } catch (IOException | InterruptedException e) {
            fail(e.getMessage());
        }
        waitUntilForemanReady(url);
        return url;
    }

    private void waitUntilForemanReady(String url) {
        int maxChecks = 30;
        int i = 0;
        while (i < maxChecks) {
            try {
                api = new Api(url, user, password);
                String version = api.getVersion();
                if (version != null) {
                    System.out.println("Foreman version " + version + " ready after " + i + " sec(s) at " + url);
                    return;
                }
            }
            catch (Exception e) {
            }
            try {
                Thread.sleep(1000);
                i++;
            } catch (InterruptedException e) {
            }
        }
        fail("Foreman failed to start in " + maxChecks + " seconds at " + url);
    }

    public UpdateFromFile updateFromFile(String resource) throws ForemanApiException {
        return updateFromFile(resource, false);
    }

    public UpdateFromFile updateFromFile(String resource, final boolean csv) throws ForemanApiException {
        File createJson = getResourceAsFile(resource);

        UpdateFromFile updater = new UpdateFromFile(Collections.singletonList(createJson.getAbsolutePath()));
        updater.server = getUrl();
        updater.user = user;
        updater.password = password;
        updater.setCsv(csv);
        updater.run();
        return updater;
    }

    public Release releaseHosts(String host, boolean force) throws ForemanApiException {
        Release release = new Release(Collections.singletonList(host));
        release.server = getUrl();
        release.user = user;
        release.password = password;
        release.setForce(force);
        release.run();
        return release;
    }

    public CreateFromFile createFromFile(String resource) throws ForemanApiException {
        return createFromFile(resource, null, false);
    }

    public CreateFromFile createFromFile(String resource, final boolean csv) throws ForemanApiException {
        return createFromFile(resource, null, csv);
    }

    public CreateFromFile createFromFile(String resource, String properties) throws ForemanApiException {
        return createFromFile(resource, properties, false);
    }

    public CreateFromFile createFromFile(String resource, String properties, final boolean csv) throws ForemanApiException {
        File createJson = getResourceAsFile(resource);

        CreateFromFile creator = new CreateFromFile(Collections.singletonList(createJson.getAbsolutePath()));
        creator.server = getUrl();
        creator.user = user;
        creator.password = password;
        creator.properties = properties;
        creator.setCsv(csv);
        creator.run();
        return creator;
    }
}

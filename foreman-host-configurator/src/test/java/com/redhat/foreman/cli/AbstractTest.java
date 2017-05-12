package com.redhat.foreman.cli;

import com.redhat.foreman.cli.docker.fixtures.ForemanContainer;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.jenkinsci.test.acceptance.docker.Resource;
import org.junit.Before;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.net.URL;

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

    protected String user = "admin";
    protected String password = "changeme";

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
        } catch (IOException e) {
            fail(e.getMessage());
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        return url;
    }

    protected void waitUntilForemanReady(String url) {
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
                Thread.currentThread().sleep(1000);
                i++;
            } catch (InterruptedException e) {
            }
        }
        fail("Foreman failed to start in " + maxChecks + " seconds at " + url);
    }


}

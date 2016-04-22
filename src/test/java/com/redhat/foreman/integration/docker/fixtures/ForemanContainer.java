package com.redhat.foreman.integration.docker.fixtures;

import org.jenkinsci.test.acceptance.docker.DockerContainer;
import org.jenkinsci.test.acceptance.docker.DockerFixture;

import java.io.IOException;
import java.net.URL;

/**
 * Runs Foreman container
 *
 */
@DockerFixture(id="foreman", ports=32768)
public class ForemanContainer extends DockerContainer {
    /**
     * URL of Foreman.
     */
    public URL getUrl() throws IOException {
        return new URL("http://"+getIpAddress()+":3000");
    }
}

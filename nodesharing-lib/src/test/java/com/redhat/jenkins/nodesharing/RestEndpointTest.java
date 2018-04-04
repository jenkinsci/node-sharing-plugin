package com.redhat.jenkins.nodesharing;

import static org.junit.Assert.*;

import org.junit.Test;

public class RestEndpointTest {

    static {
        System.setProperty("com.redhat.jenkins.nodesharing.RestEndpoint.TIMEOUT", "42");
    }

    @Test
    public void configureTimeout() {
        assertEquals(42, RestEndpoint.TIMEOUT);
    }
}

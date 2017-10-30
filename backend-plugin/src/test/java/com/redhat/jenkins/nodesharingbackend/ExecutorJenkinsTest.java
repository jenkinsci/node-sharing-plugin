package com.redhat.jenkins.nodesharingbackend;

import static org.junit.Assert.*;

import org.junit.Test;

import java.net.URL;

public class ExecutorJenkinsTest {

    @Test(expected = IllegalArgumentException.class)
    public void illegal() throws Exception {
        new ExecutorJenkins("not an URL");
    }

    @Test
    public void basics() throws Exception {
        ExecutorJenkins ej = new ExecutorJenkins("https://as.df:8080/orchestrator");

        assertEquals(new URL("https://as.df:8080/orchestrator"), ej.getUrl());
        assertEquals(new URL("https://as.df:8080/orchestrator/node-sharing-executor"), ej.getEndpointUrl());
    }
}

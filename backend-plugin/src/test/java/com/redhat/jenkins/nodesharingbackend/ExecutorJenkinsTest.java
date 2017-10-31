package com.redhat.jenkins.nodesharingbackend;

import static org.junit.Assert.*;

import org.junit.Test;

import java.net.URL;

public class ExecutorJenkinsTest {

    private static final String VALID_URL = "https://as.df:8080/orchestrator";
    private static final String VALID_NAME = "as.df";

    @Test(expected = IllegalArgumentException.class)
    public void notAnUrl() throws Exception {
        new ExecutorJenkins("not an URL", VALID_NAME);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsafeName() throws Exception {
        new ExecutorJenkins(VALID_URL, "Robert'; drop table STUDENTS;--");
    }

    @Test
    public void basics() throws Exception {
        ExecutorJenkins ej = new ExecutorJenkins(VALID_URL, VALID_NAME);

        assertEquals(VALID_NAME, ej.getName());
        assertEquals(new URL(VALID_URL), ej.getUrl());
        assertEquals(new URL("https://as.df:8080/orchestrator/node-sharing-executor"), ej.getEndpointUrl());
    }
}

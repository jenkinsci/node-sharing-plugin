package com.redhat.foreman.integration;

import static org.jenkinsci.test.acceptance.Matchers.hasContent;

import java.io.IOException;

import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.DumbSlave;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.jenkinsci.test.acceptance.po.Jenkins;
import org.jenkinsci.test.acceptance.po.JenkinsConfig;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Inject;
import com.redhat.foreman.integration.docker.fixtures.ForemanContainer;
import com.redhat.foreman.integration.po.ForemanCloudPageArea;

@WithPlugins("foreman-slave")
@WithDocker
public class ForemanIntegrationTest extends AbstractJUnitTest {
    @Inject private DockerContainerHolder<ForemanContainer> docker;

    private ForemanContainer foreman = null;
    private ForemanCloudPageArea cloud = null;

    @Before public void setUp() throws IOException {
        foreman = docker.get();
        jenkins.configure();
        cloud = addCloud(jenkins.getConfigPage());
        elasticSleep(10000);
    }

    @Test
    public void testConnection() throws IOException {
        System.out.println(foreman.getIpAddress());
        cloud.testConnection();
        waitFor(driver, hasContent("Foreman version is"), 180);
    }
    
    @Test 
    public void testProvision() {
        jenkins.save();
        //once we can provision with hammer, we can populate foreman
        // and run this test.
        DumbSlave slave = jenkins.slaves.create(DumbSlave.class);
        slave.setExecutors(1);
        slave.save();

        FreeStyleJob job = jenkins.jobs.create(FreeStyleJob.class);
        job.setLabelExpression("label1");
        job.save();

        Build b = job.scheduleBuild();
        b.waitUntilFinished(10);

    }

    private ForemanCloudPageArea addCloud(JenkinsConfig config) throws IOException {
        return config.addCloud(ForemanCloudPageArea.class)
                .name(Jenkins.createRandomName())
                .url(foreman.getUrl().toString() + "/api/")
                .user("admin")
                .password("changeme");
    }    

}


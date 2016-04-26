package com.redhat.foreman.integration;

import static org.jenkinsci.test.acceptance.Matchers.hasContent;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URISyntaxException;
import java.net.URL;

import org.codehaus.plexus.util.FileUtils;
import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.credentials.ManagedCredentials;
import org.jenkinsci.test.acceptance.plugins.ssh_credentials.SshPrivateKeyCredential;
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

/**
 * Acceptance Test Harness Test for Foreman.
 *
 */
@WithPlugins("foreman-slave")
@WithDocker
public class ForemanIntegrationTest extends AbstractJUnitTest {
    @Inject private DockerContainerHolder<ForemanContainer> docker;
    @Inject private DockerContainerHolder<JavaContainer> docker2;

    private ForemanContainer foreman = null;
    private JavaContainer sshslave = null;
    private ForemanCloudPageArea cloud = null;

    private static final int FOREMAN_CLOUD_INIT_WAIT = 180;
    private static final int PROVISION_TIMEOUT = 240;

    /**
     * Setup instance before each test.
     * @throws Exception if occurs.
     */
    @Before public void setUp() throws Exception {
        foreman = docker.get();
        sshslave = docker2.get();

        final ManagedCredentials c = new ManagedCredentials(jenkins);
        c.open();
        final SshPrivateKeyCredential sc = c.add(SshPrivateKeyCredential.class);
        sc.username.set("test");
        sc.selectEnterDirectly().privateKey.set(sshslave.getPrivateKeyString());
        c.save();

        //CS IGNORE MagicNumber FOR NEXT 2 LINES. REASON: Mock object.
        elasticSleep(6000);

        if (populateForeman(foreman.getUrl().toString(), sshslave.getCid()) != 0) {
            throw new Exception("failed to populate foreman");
        }

        jenkins.configure();
        cloud = addCloud(jenkins.getConfigPage());
        //CS IGNORE MagicNumber FOR NEXT 2 LINES. REASON: Mock object.
        elasticSleep(10000);

    }

    /**
     * Test the connection and check version.
     * @throws IOException if occurs.
     */
    @Test
    public void testConnection() throws IOException {
        System.out.println(foreman.getIpAddress());
        cloud.testConnection();
        waitFor(driver, hasContent("Foreman version is"), FOREMAN_CLOUD_INIT_WAIT);
    }

    /**
     * Verify that compatible host checker works.
     * @throws IOException if occurs.
     */
    @Test
    public void testCheckForCompatible() throws IOException {
        cloud.checkForCompatibleHosts();
        waitFor(driver, hasContent(sshslave.getCid()), FOREMAN_CLOUD_INIT_WAIT);
    }

    /**
     * Test that we can provision, build and release.
     * @throws Exception if occurs.
     */
    @Test
    public void testProvision() throws Exception {
        jenkins.save();

        DumbSlave slave = jenkins.slaves.create(DumbSlave.class, "ignore-this-slave++needed-to-enable-job-labels");
        slave.setExecutors(1);
        slave.save();

        FreeStyleJob job = jenkins.jobs.create(FreeStyleJob.class);
        job.setLabelExpression("label1");
        job.save();

        Build b = job.scheduleBuild();
        b.waitUntilFinished(PROVISION_TIMEOUT);

        jenkins.runScript("Jenkins.instance.nodes.each { it.terminate() }");

    }

    /**
     * Populate Foreman using hammer script.
     * @param server Foreman server url.
     * @param hostToCreate host name for creation.
     * @return exit code of script execution.
     * @throws URISyntaxException if occurs.
     * @throws IOException if occurs.
     * @throws InterruptedException if occurs.
     */
    private int populateForeman(String server, String hostToCreate) throws
        URISyntaxException, IOException, InterruptedException {

        URL script =
                ForemanIntegrationTest.class.getClassLoader()
                .getResource("com/redhat/foreman/integration/hammer-setup.sh");
        File tempScriptFile = File.createTempFile("hammer-setup", ".sh");
        tempScriptFile.setExecutable(true);
        FileUtils.copyURLToFile(script, tempScriptFile);

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", tempScriptFile.getAbsolutePath(),
                server,
                hostToCreate,
                sshslave.getIpAddress());

        pb.directory(tempScriptFile.getParentFile());
        pb.redirectOutput(Redirect.INHERIT);
        pb.redirectError(Redirect.INHERIT);
        pb.redirectInput(Redirect.INHERIT);
        Process p = pb.start();
        return p.waitFor();
    }

    /**
     * Add cloud to Jenkins Config.
     * @param config Jenkins Configuration Page.
     * @return a ForemanCloudPageArea.
     * @throws IOException if occurs.
     */
    private ForemanCloudPageArea addCloud(JenkinsConfig config) throws IOException {
        return config.addCloud(ForemanCloudPageArea.class)
                .name(Jenkins.createRandomName())
                .url(foreman.getUrl().toString() + "/api/")
                .user("admin")
                .password("changeme")
                .setCredentials("test");
    }

}

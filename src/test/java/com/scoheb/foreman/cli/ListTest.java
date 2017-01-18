package com.scoheb.foreman.cli;

import com.scoheb.foreman.cli.model.Architecture;
import com.scoheb.foreman.cli.model.Domain;
import com.scoheb.foreman.cli.model.Environment;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hostgroup;
import com.scoheb.foreman.cli.model.Medium;
import com.scoheb.foreman.cli.model.OperatingSystem;
import com.scoheb.foreman.cli.model.PTable;
import com.scoheb.foreman.cli.model.Parameter;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertTrue;

/**
 * Created by shebert on 11/01/17.
 */
public class ListTest extends AbstractTest {

    @Test
    public void testSearchByEnvironment()  {
        String url = getUrl();
        waitUntilForemanReady(url);
        createHosts();

        ListHosts listHosts = new ListHosts();
        listHosts.environment = "staging";
        listHosts.server = url;
        listHosts.user = user;
        listHosts.password = password;
        listHosts.run();
        assertTrue(systemOutRule.getLog().indexOf("Found 1 host") >= 0);

    }

    @Test
    public void testSearchByHostGroup()  {
        String url = getUrl();
        waitUntilForemanReady(url);
        createHosts();

        ListHosts listHosts = new ListHosts();
        listHosts.hostGroup = "staging servers";
        listHosts.server = url;
        listHosts.user = user;
        listHosts.password = password;
        listHosts.run();
        assertTrue(systemOutRule.getLog(),systemOutRule.getLog().indexOf("Found 2 host") >= 0);

    }

    public void createHosts()  {

        String url = null;
        try {
            url = rule.get().getUrl().toString() + "/api";
            Thread.currentThread().sleep(15000);
        } catch (IOException e) {
        } catch (InterruptedException e) {
        }
        Api api = new Api(url, user, password);

        Domain domain = api.createDomain("scoheb.com");

        Environment environment = api.createEnvironment("staging");
        Environment environment2 = api.createEnvironment("staging2");

        OperatingSystem os = api.createOperatingSystem("RedHat", "7", "7");
        Hostgroup hostGroup = api.createHostGroup("staging servers");

        Host host = api.createHost("stage1", "127.0.0.1",
                domain, hostGroup.id, environment.id);

        Host host2 = api.createHost("stage2", "127.0.0.2",
                domain, hostGroup.id, environment2.id);

        Parameter reservedParam = new Parameter("RESERVED", "false");
        Parameter remoteFSParam = new Parameter("JENKINS_SLAVE_REMOTEFS_ROOT", "/tmp/remoteFSRoot");
        Parameter labelParam = new Parameter("JENKINS_LABEL", "example1");

        host = api.addHostParameter(host, reservedParam);
        host = api.addHostParameter(host, remoteFSParam);
        host = api.addHostParameter(host, labelParam);

        host2 = api.addHostParameter(host2, reservedParam);
        host2 = api.addHostParameter(host2, remoteFSParam);
        host2 = api.addHostParameter(host2, labelParam);
    }

    public void createHostsFull()  {

        String url = null;
        try {
            url = rule.get().getUrl().toString() + "/api";
            Thread.currentThread().sleep(15000);
        } catch (IOException e) {
        } catch (InterruptedException e) {
        }
        Api api = new Api(url, user, password);

        Domain domain = api.createDomain("scoheb.com");

        Environment environment = api.createEnvironment("staging");
        Environment environment2 = api.createEnvironment("staging2");

        OperatingSystem os = api.createOperatingSystem("RedHat", "7", "7");
        Architecture architecture = api.getArchitecture("x86_64");
        PTable ptable = api.getPTable("Kickstart default");
        Medium medium = api.getMedium("Fedora mirror");
        Hostgroup hostGroup = api.createHostGroup("staging servers", environment.id,
                domain.id, architecture.id, os.id,
                medium.id, ptable.id, "changeme");

        Host host = api.createHost("stage1", "127.0.0.1",
                domain, hostGroup.id,
                architecture.id, os.id, medium.id, ptable.id, environment.id,
                "changeme",
                "50:7b:9d:4d:f1:12");

        Host host2 = api.createHost("stage2", "127.0.0.2",
                domain, hostGroup.id,
                architecture.id, os.id, medium.id, ptable.id, environment2.id,
                "changeme",
                "50:7b:9d:4d:f1:13");

        Parameter reservedParam = new Parameter("RESERVED", "false");
        Parameter remoteFSParam = new Parameter("JENKINS_SLAVE_REMOTEFS_ROOT", "/tmp/remoteFSRoot");
        Parameter labelParam = new Parameter("JENKINS_LABEL", "example1");

        host = api.addHostParameter(host, reservedParam);
        host = api.addHostParameter(host, remoteFSParam);
        host = api.addHostParameter(host, labelParam);

        host2 = api.addHostParameter(host2, reservedParam);
        host2 = api.addHostParameter(host2, remoteFSParam);
        host2 = api.addHostParameter(host2, labelParam);
    }
}

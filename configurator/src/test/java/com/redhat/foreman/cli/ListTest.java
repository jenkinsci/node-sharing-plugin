package com.redhat.foreman.cli;

import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Architecture;
import com.redhat.foreman.cli.model.Domain;
import com.redhat.foreman.cli.model.Environment;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Hostgroup;
import com.redhat.foreman.cli.model.Medium;
import com.redhat.foreman.cli.model.OperatingSystem;
import com.redhat.foreman.cli.model.PTable;
import com.redhat.foreman.cli.model.Parameter;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Created by shebert on 11/01/17.
 */
public class ListTest extends AbstractTest {

    @Test
    public void testQuery() throws ForemanApiException {
        String url = getUrl();
        createHosts(url);

        ListHosts listHosts = new ListHosts();
        listHosts.server = url;
        listHosts.user = user;
        listHosts.password = password;
        listHosts.query = "environment = staging";
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 1 host"));

        systemOutRule.clearLog();
        listHosts.query = "environment = prod";
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 1 host"));

        systemOutRule.clearLog();
        listHosts.query = "environment = dummy";
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 0 host"));

        systemOutRule.clearLog();
        listHosts.query = "hostgroup = \"staging servers\"";
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 2 host"));

        systemOutRule.clearLog();
        listHosts.query = "name ~ stage";
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 2 host"));

        systemOutRule.clearLog();
        listHosts.query = "params.JENKINS_LABEL = \"example1 example2\"";
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 1 host"));

        systemOutRule.clearLog();
        listHosts.query = null;
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 2 host"));
    }

    @Test
    public void testQueryCsv() throws ForemanApiException {
        String url = getUrl();
        createHosts(url);

        ListHosts listHosts = new ListHosts();
        listHosts.server = url;
        listHosts.user = user;
        listHosts.password = password;
        listHosts.query = "environment = staging";
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 1 host"));

        systemOutRule.clearLog();
        listHosts.query = "environment = prod";
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 1 host"));

        systemOutRule.clearLog();
        listHosts.query = "environment = dummy";
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 0 host"));

        systemOutRule.clearLog();
        listHosts.query = "hostgroup = \"staging servers\"";
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 2 host"));

        systemOutRule.clearLog();
        listHosts.query = "name ~ stage";
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 2 host"));

        systemOutRule.clearLog();
        listHosts.query = "params.JENKINS_LABEL = \"example1 example2\"";
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 1 host"));

        systemOutRule.clearLog();
        listHosts.query = null;
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString("Found 2 host"));
    }

    @Test
    public void testListCsv() throws ForemanApiException {
        String url = getUrl();
        createHosts(url);

        systemOutRule.clearLog();
        ListHosts listHosts = new ListHosts();
        listHosts.server = url;
        listHosts.user = user;
        listHosts.password = password;
        listHosts.setCsv(true);
        listHosts.run();
        assertThat(systemOutRule.getLog(), containsString(
                "stage1.scoheb.com;example1 example2;/tmp/remoteFSRoot;~/unix.sh"));
        assertThat(systemOutRule.getLog(), containsString(
                "stage2.scoheb.com;example2;/tmp/remoteFSRoot;~/unix.sh"));
    }

    public void createHosts(String url) throws ForemanApiException {

        Api api = new Api(url, user, password);

        Domain domain = api.createDomain("scoheb.com");

        Environment environment = api.createEnvironment("staging");
        Environment environment2 = api.createEnvironment("prod");

        OperatingSystem os = api.createOperatingSystem("RedHat", "7", "7");
        Hostgroup hostGroup = api.createHostGroup("staging servers");

        Host host = api.createHost("stage1", "127.0.0.1",
                domain, hostGroup.id, environment.id);

        Host host2 = api.createHost("stage2", "127.0.0.2",
                domain, hostGroup.id, environment2.id);

        Parameter reservedParam = new Parameter("RESERVED", "false");
        Parameter remoteFSParam = new Parameter("JENKINS_SLAVE_REMOTEFS_ROOT", "/tmp/remoteFSRoot");
        Parameter labelParam1 = new Parameter("JENKINS_LABEL", "example1 example2");
        Parameter labelParam2 = new Parameter("JENKINS_LABEL", "example2");
        Parameter javaPathParam = new Parameter("JENKINS_SLAVE_JAVA_PATH", "~/unix.sh");

        host = api.addHostParameter(host, reservedParam);
        host = api.addHostParameter(host, remoteFSParam);
        host = api.addHostParameter(host, labelParam1);
        host = api.addHostParameter(host, javaPathParam);

        host2 = api.addHostParameter(host2, reservedParam);
        host2 = api.addHostParameter(host2, remoteFSParam);
        host2 = api.addHostParameter(host2, labelParam2);
        host2 = api.addHostParameter(host2, javaPathParam);
    }

    public void createHostsFull(String url) throws ForemanApiException {

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
        Parameter javaPathParam = new Parameter("JENKINS_SLAVE_JAVA_PATH", "~/unix.sh");

        host = api.addHostParameter(host, reservedParam);
        host = api.addHostParameter(host, remoteFSParam);
        host = api.addHostParameter(host, labelParam);
        host = api.addHostParameter(host, javaPathParam);

        host2 = api.addHostParameter(host2, reservedParam);
        host2 = api.addHostParameter(host2, remoteFSParam);
        host2 = api.addHostParameter(host2, labelParam);
        host2 = api.addHostParameter(host2, javaPathParam);
    }
}

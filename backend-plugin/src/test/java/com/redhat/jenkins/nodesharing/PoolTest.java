package com.redhat.jenkins.nodesharing;

import hudson.EnvVars;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Map;

import static com.redhat.jenkins.nodesharing.Pool.CONFIG_REPO_PROPERTY_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author ogondza.
 */
public class PoolTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readConfigFromRepo() throws Exception {
        injectDummyConfigRepo();
        Map<String, String> config = Pool.getInstance().getConfig();
        assertEquals("https://dummy.test", config.get("orchestrator.url"));
    }
    
    @Test
    public void testPopulateComputers() throws Exception {
        
    }

    private void injectDummyConfigRepo() throws Exception {
        File orig = new File(getClass().getResource("dummy_config_repo").toURI());
        assertTrue(orig.isDirectory());
        File repo = tmp.newFolder();
        FileUtils.copyDirectory(orig, repo);

        StreamTaskListener listener = new StreamTaskListener(System.err, Charset.defaultCharset());
        GitClient git = Git.with(listener, new EnvVars()).in(repo).using("git").getClient();
        git.init();
        git.add("*");
        git.commit("Init");

        System.setProperty(CONFIG_REPO_PROPERTY_NAME, repo.getAbsolutePath());
        Pool.Updater.getInstance().doRun();
    }
}

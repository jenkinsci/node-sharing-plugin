package com.redhat.jenkins.nodesharingbackend;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.Util;
import org.junit.ClassRule;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class JCasCCompatibilityTest {

    @ClassRule
    @ConfiguredWithCode("JCasCConfiguration.yaml")
    public static JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    public void JCasCConfigurationTest() throws Exception {
        assertThat(Pool.getInstance().getConfigRepoUrl(), is("test-repo"));
        assertThat(Pool.getInstance().getConfigRepo(), is("test-repo"));
        assertThat(Pool.getInstance().getUsername(), is("test-user"));
        assertThat(Pool.getInstance().getPassword(), is("test-pwd"));
    }

    @Test
    public void JCasCExportTest() throws Exception {
        assertThat(Util.toYamlString(
                Util.getUnclassifiedRoot(new ConfigurationContext(ConfiguratorRegistry.get())).get("nodeSharingPool")),
                is(Util.toStringFromYamlFile(this, "ExportExpectedOutput.yaml")));
    }
}

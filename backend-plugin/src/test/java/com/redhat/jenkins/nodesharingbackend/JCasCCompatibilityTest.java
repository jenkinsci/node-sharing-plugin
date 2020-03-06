package com.redhat.jenkins.nodesharingbackend;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.Util;
import io.jenkins.plugins.casc.model.CNode;
import org.jenkinsci.Symbol;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.annotation.Annotation;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

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

        // Get @Symbol value for Pool class
        CNode yourAttribute = null;
        for(Annotation val : Pool.class.getAnnotations()) {
            if (val instanceof org.jenkinsci.Symbol) {
                yourAttribute = Util.getUnclassifiedRoot(
                        new ConfigurationContext(ConfiguratorRegistry.get())).get(((Symbol) val).value()[0]);
            }
        }

        assertThat(Util.toYamlString(yourAttribute),
                is(Util.toStringFromYamlFile(this, "ExportExpectedOutput.yaml")));
    }
}

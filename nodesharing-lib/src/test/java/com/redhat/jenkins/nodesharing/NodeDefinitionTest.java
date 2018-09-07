package com.redhat.jenkins.nodesharing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NodeDefinitionTest {

    @Test
    public void testNodeDefinitionXml() {
        String nodeXmlDef = "<com.redhat.jenkins.nodesharingfrontend.SharedNode>\n" +
                "  <name>test.redhat.com</name>\n" +
                "  <description/>\n" +
                "  <remoteFS>/var/jenkins-workspace</remoteFS>\n" +
                "  <numExecutors>1</numExecutors>\n" +
                "  <mode>EXCLUSIVE</mode>\n" +
                "  <launcher class=\"hudson.slaves.CommandLauncher\">\n" +
                "    <agentCommand />\n" +
                "  </launcher>\n" +
                "  <label>test</label>\n" +
                "  <nodeProperties/>\n" +
                "</com.redhat.jenkins.nodesharingfrontend.SharedNode>";
        NodeDefinition.Xml xmlDef = new NodeDefinition.Xml("test.xml", nodeXmlDef);
        assertEquals(xmlDef.getName(), "test");
        assertEquals(xmlDef.getLabel(), "test");
        assertEquals(xmlDef.getDeclaringFileName(), "test.xml");
        assertEquals(xmlDef.getDefinition(), nodeXmlDef);

        nodeXmlDef = "<test";
        try {
            xmlDef = new NodeDefinition.Xml("test.xml", nodeXmlDef);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().startsWith("Cannot parse xml: "));
        }

        nodeXmlDef = "<com.redhat.jenkins.nodesharingfrontend.SharedNode>\n" +
                "  <name>test.redhat.com</name>\n" +
                "  <description/>\n" +
                "  <remoteFS>/var/jenkins-workspace</remoteFS>\n" +
                "  <numExecutors>1</numExecutors>\n" +
                "  <mode>EXCLUSIVE</mode>\n" +
                "  <launcher class=\"hudson.slaves.CommandLauncher\">\n" +
                "    <agentCommand />\n" +
                "  </launcher>\n" +
                "  <nodeProperties/>\n" +
                "</com.redhat.jenkins.nodesharingfrontend.SharedNode>";
        try {
            xmlDef = new NodeDefinition.Xml("test.xml", nodeXmlDef);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().startsWith("No labels found in "));
        }

        nodeXmlDef = "<com.redhat.jenkins.nodesharingfrontend.SharedNode>\n" +
                "  <name>test.redhat.com</name>\n" +
                "  <description/>\n" +
                "  <remoteFS>/var/jenkins-workspace</remoteFS>\n" +
                "  <numExecutors>1</numExecutors>\n" +
                "  <mode>EXCLUSIVE</mode>\n" +
                "  <launcher class=\"hudson.slaves.CommandLauncher\">\n" +
                "    <agentCommand />\n" +
                "  </launcher>\n" +
                "  <label></label>\n" +
                "  <nodeProperties/>\n" +
                "</com.redhat.jenkins.nodesharingfrontend.SharedNode>";
        try {
            xmlDef = new NodeDefinition.Xml("test.xml", nodeXmlDef);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("No labels specified for node test"));
        }
    }
}

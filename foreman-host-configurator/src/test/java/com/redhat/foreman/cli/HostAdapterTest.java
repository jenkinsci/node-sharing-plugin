package com.redhat.foreman.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.HostTypeAdapter;
import com.redhat.foreman.cli.model.Hosts;
import org.junit.Test;

import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;

/**
 * Created by shebert on 20/01/17.
 */
public class HostAdapterTest {

    String json = "{\n" +
            "      \'name\': \'solaris-test-1\',\n" +
            "      \'labels\': \'solaris10\',\n" +
            "      \'remoteFs\': \'/home/jenkins\'\n" +
            "}";
    String jsonList = "{\n" +
            "  \"hosts\": [\n" +
            "    {\n" +
            "      \"name\": \"scott1.localdomain\",\n" +
            "      \"labels\": \"scott scott\",\n" +
            "      \"remoteFs\": \"/tmp/scott\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"scott2.localdomain\",\n" +
            "      \"labels\": \"scott scott\",\n" +
            "      \"remoteFs\": \"/tmp/scott\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    @Test
    public void testSingleHost() {
        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Host.class, new HostTypeAdapter());
        gsonBuilder.setPrettyPrinting();

        final Gson gson = gsonBuilder.create();
        final Host host = gson.fromJson(json, Host.class);
        assertNotNull(host);
        assertNotNull(host.parameters);
        assertTrue(host.parameters.size() > 0);
    }

    @Test
    public void testHostList() {
        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Host.class, new HostTypeAdapter());
        gsonBuilder.setPrettyPrinting();

        final Gson gson = gsonBuilder.create();
        final Hosts hosts = gson.fromJson(jsonList, Hosts.class);
        assertNotNull(hosts);
        assertNotNull(hosts.getHosts().get(0));
        assertNotNull(hosts.getHosts().get(0).parameters);
        assertTrue(hosts.getHosts().get(0).parameters.size() > 0);
    }

    @Test
    public void testWriteHostList() {
        final GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Host.class, new HostTypeAdapter());
        gsonBuilder.setPrettyPrinting();

        final Gson gson = gsonBuilder.create();
        final Hosts hosts = gson.fromJson(jsonList, Hosts.class);
        assertNotNull(hosts);
        assertNotNull(hosts.getHosts().get(0));
        assertNotNull(hosts.getHosts().get(0).parameters);
        assertTrue(hosts.getHosts().get(0).parameters.size() > 0);

        String hostsJson = gson.toJson(hosts);
        assertNotNull(hostsJson);
        System.out.println(hostsJson);
    }

}

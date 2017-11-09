/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharingfrontend;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Items;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.RootAction;
import hudson.model.labels.LabelAtom;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Receive and send REST commands from/to Orchestrator Jenkins.
 */
@Extension
@Restricted(NoExternalUse.class)
// TODO Check permission
public class Api implements RootAction {
    private static final String HIDDEN = null;

    private WebTarget base = null;

    public Api() {}

    public Api(String OrchestratorUrl) {
        ClientConfig clientConfig = new ClientConfig();

        // TODO HTTP autentization
        //HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, Secret.toString(password));
        //clientConfig.register(feature);

        clientConfig.register(JacksonFeature.class);
        Client client = ClientBuilder.newClient(clientConfig);

        // Define a quite defensive timeouts
        client.property(ClientProperties.CONNECT_TIMEOUT, 60000);   // 60s
        client.property(ClientProperties.READ_TIMEOUT,    300000);  // 5m
        base = client.target(OrchestratorUrl);
    }

    @Nonnull
    public static Api getInstance() {
        ExtensionList<Api> list = Jenkins.getInstance().getExtensionList(Api.class);
        assert list.size() == 1;
        return list.iterator().next();
    }

    @Override
    public String getIconFileName() {
        return HIDDEN;
    }

    @Override
    public String getDisplayName() {
        return HIDDEN;
    }

    @Override
    public String getUrlName() {
        return "node-sharing-executor";
    }

    //// Outgoing

    /**
     * Query Executor Jenkins to report the status of shared node.
     *
     * @param name Name of the node to be queried.
     * @return Node status.
     */
    @CheckForNull
    public Object nodeStatus(@Nonnull final String name) {
        return null;
    }

    /**
     * Query Executor Jenkins to report the status of executed item.
     *
     * @param id ID of the run to be queried.
     * @return Item status.
     */
    @CheckForNull
    public Object runStatus(@Nonnull final String id) {
        return null;
    }

    /**
     * Put the queue items to Orchestrator
     */
    public void putBacklogToOrchestrator() {
        Set<LabelAtom> sla = new TreeSet<LabelAtom>();

        // TODO Get List of provided labels
        Set<LabelAtom> sla_tmp = new TreeSet<LabelAtom>();
        sla_tmp.add(new LabelAtom("foo"));
        sla_tmp.add(new LabelAtom("bar"));
        for(LabelAtom la : sla_tmp) {
            sla.add(la);
        }
        sla_tmp = new TreeSet<LabelAtom>();
        sla_tmp.add(new LabelAtom("test"));
        for(LabelAtom la : sla_tmp) {
            sla.add(la);
        }
        // TODO Remove above with proper impl.

        List<Queue.Item> qi = new ArrayList<Queue.Item>();

        for(Queue.Item i : Jenkins.getInstance().getQueue().getItems()) {
            if(i.getAssignedLabel().matches(sla)) {
                 qi.add(i);
            }
        }

        // TODO Prepare the requests from qi

        // TODO Post the request to the Orchestrator
    }

    /**
     * Request to Discover the state of the Orchestrator
     * @return
     */
    public String doDiscover() {
        return "";
    }

    public String doRelease(@Nonnull final String name) {
        // TODO do release
        return "";
    }

    //// Incoming

    /**
     * Request to execute #Item from the queue
     */
    @RequirePOST
    public void doExecution(@Nonnull @QueryParameter final Node computer,
                            @Nonnull @QueryParameter final String id) {
        // TODO Create a Node based on the info and execute the Item
    }

    /**
     * Immediately return node to orchestrator. (Nice to have feature)
     *
     * @param name Name of the node to be returned.
     */
    @RequirePOST
    public void doReturnNode(@Nonnull @QueryParameter final String name) {
        throw new NotSupportedException();
/*
        Computer c = Jenkins.getInstance().getComputer(name);
        if (!(c instanceof SharedComputer)) {
            // TODO computer not reservable
            return;
        }
        SharedComputer computer = (SharedComputer) c;
        ReservationTask.ReservationExecutable executable = computer.getReservation();
        if (executable == null) {
            // TODO computer not reserved
            return;
        }
        // TODO The owner parameter is in no way sufficient proof the client is authorized to release this
        executable.complete(owner, state);
*/
    }
}

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
import hudson.model.Node;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.ws.rs.NotSupportedException;

/**
 * Receive and send REST commands from/to Orchestrator Jenkins.
 */
@Extension
@Restricted(NoExternalUse.class)
// TODO Check permission
public class Api implements RootAction {
    private static final String HIDDEN = null;

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
     * Query Executor Jenkins to report the status of executed run.
     *
     * @param run ID of the run to be queried.
     * @return Item status.
     */
    @CheckForNull
    public Object runStatus(@Nonnull final String run) {
        return null;
    }

    //// Incoming

    /**
     * Dummy request to test the connection/compatibility.
     */
    @RequirePOST
    public void doExecution(@Nonnull @QueryParameter final Node computer,
                            @Nonnull @QueryParameter final String Item) {
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

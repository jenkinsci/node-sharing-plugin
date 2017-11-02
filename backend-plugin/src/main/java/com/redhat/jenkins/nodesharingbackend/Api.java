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
package com.redhat.jenkins.nodesharingbackend;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Receive and send REST commands from/to executor Jenkinses.
 */
@Extension
@Restricted(NoExternalUse.class)
public class Api implements RootAction {
    private static final String HIDDEN = null;

    public static @Nonnull Api getInstance() {
        ExtensionList<Api> list = Jenkins.getInstance().getExtensionList(Api.class);
        assert list.size() == 1;
        return list.iterator().next();
    }

    @Override public String getIconFileName() {
        return HIDDEN;
    }

    @Override public String getDisplayName() {
        return HIDDEN;
    }

    @Override public String getUrlName() {
        return "node-sharing-orchestrator";
    }

    //// Outgoing

    /**
     * Signal to Executor Jenkins to start using particular node.
     *
     * @param owner Jenkins instance the node is reserved for.
     * @param node Node to be reserved.
     */
    public void utilizeNode(@Nonnull ExecutorJenkins owner, @Nonnull SharedNode node) {

    }

    /**
     * Query executor Jenkins to report shared hosts it uses.
     *
     * It should be the Orchestrator who has an authority to say that but this is to query executor's view of things.
     * Most useful when Orchestrator boots after crash with all the reservation info possibly lost or outdated.
     *
     * @param owner Jenkins instance to query.
     * @return List of host names the instance is using.
     */
    public @Nonnull Collection<String> reportUsage(@Nonnull ExecutorJenkins owner) {
        return null;
    }

    /**
     * Determine whether the host is still used by executor.
     *
     * Ideally, the host is utilized between {@link #utilizeNode(ExecutorJenkins, SharedNode)} was send and
     * {@link #doReturnNode(String, ExecutorJenkins, String)} was received but in case of any of the requests failed to be delivered for some
     * reason, there is this way to recheck. Note this has to recognise Jenkins was stopped or plugin was uninstalled so
     * we can not rely on node-sharing API on Executor end.
     *
     * @param owner Jenkins instance to query.
     * @param node The node to query.
     * @return true if the computer is still connected there, false if we know it is not, null otherwise.
     */
    public Boolean isUtilized(@Nonnull ExecutorJenkins owner, @Nonnull SharedNode node) {
        return true;
    }

    //// Incoming

    /**
     * Dummy request to test the connection/compatibility.
     */
    @RequirePOST
    public void doDiscover() {
        // TODO In  config-repo url and executor url for sanity check
        // TODO Out error if sanity check failed, labels and TBD for success
    }

    /**
     * Report workload to be executed on orchestrator for particular executor master.
     */
    @RequirePOST
    public void doReportWorkload() {
        // TODO In set of queue items to be build
        // TODO Out nothing
    }

    /**
     * Return node to orchestrator when no longer needed.
     *
     * @param name Name of the node to be returned.
     * @param state
     *      'OK' if the host was used successfully,
     *      'FAILED' when executor failed to get the node onlin,
     *      other values are ignored.
     */
    @RequirePOST
    public void doReturnNode(@QueryParameter String name, @QueryParameter ExecutorJenkins owner, @QueryParameter String state) {
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
        executable.complete(owner, state);
    }
}

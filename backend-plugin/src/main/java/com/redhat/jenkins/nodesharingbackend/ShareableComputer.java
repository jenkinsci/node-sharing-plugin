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

import hudson.Functions;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.remoting.Channel;
import hudson.security.Permission;
import hudson.slaves.EphemeralNode;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.Futures;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.LogRecord;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * Placeholder computer representing shareable computer.
 *
 * The computer is always "up" with no channel to the real system. The purpose of this abstraction is to schedule implement
 * label aware queuing.
 *
 * @see ReservationTask
 */
@Restricted(NoExternalUse.class)
public class ShareableComputer extends SlaveComputer implements EphemeralNode {
    private final Channel channel;
    /*package*/ ShareableComputer(Slave slave) {
        super(slave);
        // A lot of Jenkins abstractions presumes Computers are either SlaveComputers or a single MasterComputer which
        // effectively forces us to implement ShareableComputer as SlaveComputer. That, however, needs to have Channel associated
        // but again, the API enforces that to be a "real" Channel which is undesirable. Constructing such channel to do
        // nothing turned to be tricky as it perform transport negotiation in constructor so we are creating our Dummy
        // subtype without invoking constructor here.
        String typeName = NoopChannel.class.getName().replace("$", "_-");
        channel = (Channel) Jenkins.XSTREAM2.fromXML(
                "<?xml version='1.0' encoding='UTF-8'?>" +
                "<" + typeName + ">" +
                "  <name>" + slave.getNodeName() + "</name>" +
                "</" + typeName + ">"
        );
    }

    /**
     * Get the reservation task occupying this computer.
     *
     * @return The task or null when the computer is idle.
     */
    public @CheckForNull ReservationTask.ReservationExecutable getReservation() {
        List<Queue.Executable> executables = new ArrayList<>();
        for (Executor executor : getExecutors()) {
            Queue.Executable executable = executor.getCurrentExecutable();
            if (executable != null) {
                executables.add(executable);
            }
        }
        switch (executables.size()) {
            case 0: return null;
            case 1:
                Queue.Executable executable = executables.get(0);
                if (!(executable instanceof ReservationTask.ReservationExecutable)) {
                    throw new IllegalStateException(
                            "Unknown task running on ShareableComputer: " + executable.getClass().getName()
                    );
                }
                return (ReservationTask.ReservationExecutable) executable;
            default: throw new IllegalStateException("More than a single task running on ShareableComputer: " + executables);
        }
    }

    @Override
    public @Nonnull String getName() {
        return nodeName;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public @Nonnull String getDisplayName() {
        return nodeName;
    }

    public RetentionStrategy getRetentionStrategy() {
        return RetentionStrategy.NOOP;
    }

    public Boolean isUnix() {
        return !Functions.isWindows();
    }

    @Override
    public HttpResponse doDoDelete() throws IOException {
        throw HttpResponses.status(SC_BAD_REQUEST);
    }

    public void doDelete() { // Override delete.jelly
        throw HttpResponses.status(SC_BAD_REQUEST);
    }

    @Override
    public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
        throw HttpResponses.status(SC_BAD_REQUEST);
    }

    public void doConfigure() { // Override configure.jelly
        throw HttpResponses.status(SC_BAD_REQUEST);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return false;
    }

    @Override
    public Channel getChannel() {
        return channel;
    }

    @Override
    public Charset getDefaultCharset() {
        return Charset.defaultCharset();
    }

    public List<LogRecord> getLogRecords() throws IOException, InterruptedException {
        return Collections.emptyList();
    }

    @RequirePOST
    public void doLaunchSlaveAgent(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        rsp.sendError(SC_NOT_FOUND);
    }

    protected Future<?> _connect(boolean forceReconnect) {
        return Futures.precomputed(null);
    }

    @Override
    public ShareableNode asNode() {
        return getNode();
    }

    @Override
    public @CheckForNull ShareableNode getNode() {
        return (ShareableNode) super.getNode();
    }

}

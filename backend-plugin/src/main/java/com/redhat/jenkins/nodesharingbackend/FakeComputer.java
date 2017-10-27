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
import hudson.model.Slave;
import hudson.remoting.Callable;
import hudson.remoting.CallableFilter;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.ChannelProperty;
import hudson.remoting.CommandTransport;
import hudson.remoting.JarCache;
import hudson.remoting.forward.ListeningPort;
import hudson.security.Permission;
import hudson.slaves.EphemeralNode;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import hudson.util.Futures;
import jenkins.model.Jenkins;
import org.jenkinsci.remoting.CallableDecorator;
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
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.LogRecord;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * Placeholder computer representing shareable computer.
 *
 * The computer is always "up" with no channel so the real system. The purpose of this abstraction is to schedule here
 */
@Restricted(NoExternalUse.class)
public class FakeComputer extends SlaveComputer implements EphemeralNode {
    private final Channel channel;
    /*package*/ FakeComputer(Slave slave) {
        super(slave);
        // A lot of Jenkins abstractions presumes Computers are either SlaveComputers or a single MasterComputer which
        // effectively forces us to implement FakeComputer as SlaveComputer. That, however, needs to have Channel associated
        // but again, the API enforces that to be a "real" channel which is undesirable. Constructing such channel to do
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

    @Override
    public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
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
    public SharedNode asNode() {
        return getNode();
    }

    @Override
    public @CheckForNull SharedNode getNode() {
        return (SharedNode) super.getNode();
    }

    /**
     * Channel that does nothing but still exists.
     *
     * We need computer to have channel associated so it appears online to Jenkins but have no agent to connect it to.
     * Using local channel for all FakeComputers sounds strange but not outright dangerous. Unfortunately, the types of
     * local channel and SlaveComputer#getChannel are not compatible.
     *
     * This channel is noop/throw-all-the-time to do nothing at all.
     */
    @SuppressWarnings({"unused", "deprecation"}) // Instantiated via serialization
    public static class NoopChannel extends Channel {

        private NoopChannel(ChannelBuilder settings, CommandTransport transport) throws IOException {
            super(settings, transport);
        }

        @Override public String getName() {
            return super.getName();
        }

        @Override public String toString() {
            return "Fake channel " + super.getName();
        }

        @Override public boolean isInClosed() { return false; }
        @Override public boolean isOutClosed() { return false; }
        @Override public boolean isClosingOrClosed() { return super.isClosingOrClosed(); }

        @Override public JarCache getJarCache() { return null; }
        @Override public void setJarCache(JarCache jarCache) { }

        @Override public synchronized void join() throws InterruptedException { }
        @Override public synchronized void join(long timeout) throws InterruptedException { }

        @Override public void addListener(Listener l) { }
        @Override public boolean removeListener(Listener l) { return false; }

        @Override public void addLocalExecutionInterceptor(CallableDecorator decorator) { }
        @Override public void removeLocalExecutionInterceptor(CallableDecorator decorator) { }

        @Override public void addLocalExecutionInterceptor(CallableFilter filter) { }
        @Override public void removeLocalExecutionInterceptor(CallableFilter filter) { }

        @Override public boolean isRestricted() { return true; }
        @Override public void setRestricted(boolean b) { }

        @Override public boolean isRemoteClassLoadingAllowed() { return false; }
        @Override public void setRemoteClassLoadingAllowed(boolean b) { }

        @Override public boolean isArbitraryCallableAllowed() { return false; }
        @Override public void setArbitraryCallableAllowed(boolean b) { }

        @Override public void resetPerformanceCounters() { }
        @Override public void dumpPerformanceCounters(PrintWriter w) throws IOException { }

        @Override public synchronized void close() throws IOException { }
        @Override public synchronized void close(Throwable diagnosis) throws IOException { }

        @Override public Object getProperty(Object key) { return null; }
        @Override public <T> T getProperty(ChannelProperty<T> key) { return null; }

        @Override public synchronized Object waitForProperty(Object key) throws InterruptedException { return null; }
        @Override public <T> T waitForProperty(ChannelProperty<T> key) throws InterruptedException { return null; }

        @Override public synchronized Object setProperty(Object key, Object value) { return null; }
        @Override public <T> T setProperty(ChannelProperty<T> key, T value) { return null; }

        @Override public Object getRemoteProperty(Object key) { return null; }
        @Override public <T> T getRemoteProperty(ChannelProperty<T> key) { return null; }

        @Override public Object waitForRemoteProperty(Object key) throws InterruptedException { return null; }
        @Override public <T> T waitForRemoteProperty(ChannelProperty<T> key) throws InterruptedException { return null; }

        @Override public void setMaximumBytecodeLevel(short level) throws IOException, InterruptedException { }

        @Override public <T> T export(Class<T> type, T instance) {
            throw new UnsupportedOperationException();
        }

        @Override public void pin(Object instance) {
            throw new UnsupportedOperationException();
        }

        @Override public void pinClassLoader(ClassLoader cl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean preloadJar(Callable<?, ?> classLoaderRef, Class... classesInJar) throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean preloadJar(ClassLoader local, Class... classesInJar) throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean preloadJar(ClassLoader local, URL... jars) throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V, T extends Throwable> V call(Callable<V, T> callable) throws IOException, T, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V, T extends Throwable> hudson.remoting.Future<V> callAsync(Callable<V, T> callable) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override public void terminate(IOException e) {
            throw new UnsupportedOperationException();
        }

        @Override public OutputStream getUnderlyingOutput() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ListeningPort createLocalToRemotePortForwarding(int recvPort, String forwardHost, int forwardPort) throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ListeningPort createRemoteToLocalPortForwarding(int recvPort, String forwardHost, int forwardPort) throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override public void syncIO() throws IOException, InterruptedException { }
        @Override public void syncLocalIO() throws InterruptedException { }

// Return type is not accessible
//        @Override public ExportTable.ExportList startExportRecording() {
//            throw new UnsupportedOperationException();
//        }
        @Override public void dumpExportTable(PrintWriter w) throws IOException { }

        @Override public long getLastHeard() { return 0; }
    }
}

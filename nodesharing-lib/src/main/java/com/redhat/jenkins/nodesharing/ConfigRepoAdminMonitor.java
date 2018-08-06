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
package com.redhat.jenkins.nodesharing;

import com.google.common.annotations.VisibleForTesting;
import hudson.AbortException;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import hudson.model.AdministrativeMonitor;
import hudson.util.CopyOnWriteMap;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Report problems syncing Config Repository/-ies.
 *
 * @author ogondza.
 */
public class ConfigRepoAdminMonitor extends AdministrativeMonitor {

    private final @Nonnull Map<String, Throwable> errors = new CopyOnWriteMap.Hash<>();

    public static ConfigRepoAdminMonitor getInstance() {
        return ExtensionList.lookup(ConfigRepoAdminMonitor.class).get(0);
    }

    public ConfigRepoAdminMonitor() {
        // Configure UI logger for ease of maintenance
        LogRecorderManager log = Jenkins.getActiveInstance().getLog();
        LogRecorder recorder = log.getLogRecorder("node-sharing");
        if (recorder == null) {
            recorder = new LogRecorder("node-sharing");
            recorder.targets.add(new LogRecorder.Target("com.redhat.jenkins.nodesharing", Level.INFO));
            recorder.targets.add(new LogRecorder.Target("com.redhat.jenkins.nodesharingbackend", Level.INFO));
            recorder.targets.add(new LogRecorder.Target("com.redhat.jenkins.nodesharingfrontend", Level.INFO));
            log.logRecorders.put("node-sharing", recorder);
        }
    }

    @Override
    public boolean isActivated() {
        return !errors.isEmpty();
    }

    @Override
    public @Nonnull String getDisplayName() {
        return "Node Sharing Monitor";
    }

    public void clear() {
        errors.clear();
    }

    public void report(@Nonnull String context, @Nonnull Throwable ex) {
        errors.put(context, ex);
    }

    public @Nonnull Map<String, Throwable> getErrors() {
        return new HashMap<>(errors);
    }

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    @Restricted(NoExternalUse.class)
    public static void checkNodeSharingRole() throws AbortException {
        if (Functions.getIsUnitTest()) return; // jth-tests rely on this mode in fact
        _checkNodeSharingRole();
    }

    @VisibleForTesting
    /*package*/ static void _checkNodeSharingRole() throws AbortException {
        boolean backend, frontend;
        try {
            Class.forName("com.redhat.jenkins.nodesharingbackend.Api");
            backend = true;
        } catch (ClassNotFoundException e) {
            backend = false;
        }

        try {
            Class.forName("com.redhat.jenkins.nodesharingfrontend.Api");
            frontend = true;
        } catch (ClassNotFoundException e) {
            frontend = false;
        }

        if (backend && frontend) throw new AbortException(
                "Single Jenkins can not play a role of both Node Sharing Executor and Orchestrator"
        );

        if (!backend && !frontend) throw new AssertionError();
    }
}

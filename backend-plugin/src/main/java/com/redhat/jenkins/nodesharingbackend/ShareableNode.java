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

import com.google.common.annotations.VisibleForTesting;
import com.redhat.jenkins.nodesharing.NodeDefinition;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.util.Collections;

/**
 * @author ogondza.
 */
@Restricted(NoExternalUse.class)
public final class ShareableNode extends Slave implements EphemeralNode {
    private static final long serialVersionUID = 1864241962205144748L;

    private final transient Object nodeSharingAttributesLock = new Object();
    @GuardedBy("nodeSharingAttributesLock")
    private @Nonnull NodeDefinition nodeDefinition;

    public ShareableNode(@Nonnull NodeDefinition def) throws Descriptor.FormException, IOException {
        super(def.getName(), def.getName(), "/unused", 1, Mode.EXCLUSIVE, def.getLabel(), new NoopLauncher(), RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());
        nodeDefinition = def;
    }

    @Override public Computer createComputer() {
        return new ShareableComputer(this);
    }

    @Override public ShareableNode asNode() {
        return this;
    }

    @Nonnull public NodeDefinition getNodeDefinition() {
        return nodeDefinition;
    }

    @Override public CauseOfBlockage canTake(Queue.BuildableItem item) {
        return item.task instanceof ReservationTask ? null : RESERVATION_TASKS_ONLY;
    }

    private static final CauseOfBlockage RESERVATION_TASKS_ONLY = new CauseOfBlockage() {
        @Override public String getShortDescription() {
            return "Reservations tasks only";
        }
    };

    /**
     * Delete the node now if idle or once it becomes idle.
     */
    public void deleteWhenIdle() {
        Computer c = toComputer();
        if (c != null) {
            c.setTemporarilyOffline(true, PENDING_DELETION);
            if (!c.isIdle()) {
                // Postpone deletion until idle.
                // Note that RunListener is not invoked as we are not executing Runs and using ExecutorListener is hackish
                // as it is invoked before the computer is considered idle. Keeping it temp-offline then to be collected
                // by DanglingNodeDeleter.
                return;
            }
        }

        try {
            Jenkins j = Jenkins.getInstance();
            j.removeNode(this);
        } catch (IOException e) {
            // delay as if not idle
        }
    }

    /**
     * Delayed deletion promised by {@link ShareableNode#deleteWhenIdle()}.
     */
    @Extension
    public static final class DanglingNodeDeleter extends PeriodicWork {

        @Override
        public long getRecurrencePeriod() {
            return HOUR;
        }

        @VisibleForTesting
        @Override
        public void doRun() throws Exception {
            for (Node node : Jenkins.getInstance().getNodes()) {
                if (node instanceof ShareableNode && ((ShareableNode) node).canBeDeleted()) {
                    ((ShareableNode) node).deleteWhenIdle();
                }
            }
        }

    }
    /**
     * The node is no longer occupied.
     *
     * @return true if the machine was pending delete and
     */
    public boolean canBeDeleted() {
        Computer c = toComputer();
        return c == null || (c.getOfflineCause() == PENDING_DELETION && c.isIdle());
    }

    private static final OfflineCause PENDING_DELETION= new OfflineCause.UserCause(
            User.getUnknown(), "Node is pending deletion"
    );

    /**
     * Update current node with the configuration of a new one.
     *
     * The node is not replaced not to interrupt running builds.
     *
     * @param definition New configuration to populate.
     */
    public void updateBy(@Nonnull NodeDefinition definition) {
        assert getNodeName().equals(definition.getName());

        synchronized (nodeSharingAttributesLock) {
            assert definition instanceof NodeDefinition.Xml;

            this.nodeDefinition = definition;
            try {
                setLabelString(definition.getLabel());
            } catch (IOException ex) {
                throw new Error("Never actually thrown");
            }
        }
    }

    public static final class NoopLauncher extends ComputerLauncher {
        @Override
        public boolean isLaunchSupported() {
            return false;
        }

        @Override
        public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
            // NOOP
        }
    }
}

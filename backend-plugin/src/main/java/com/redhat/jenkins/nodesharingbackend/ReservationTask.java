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

import com.redhat.jenkins.nodesharing.ExecutorJenkins;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.queue.AbstractQueueTask;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;
import org.acegisecurity.AccessDeniedException;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Phony queue task that simulates build execution on executor Jenkins.
 *
 * All requests to reserve a host are modeled as queue items so they are prioritized by Jenkins as well as and assigned
 * to {@link SharedComputer}s according to labels. Similarly, when computer is occupied by this task it means the host is
 * effectively reserved for executor Jenkins that has created this.
 *
 * @author ogondza.
 */
public class ReservationTask extends AbstractQueueTask {
    private final @Nonnull ExecutorJenkins jenkins;
    private final @Nonnull Label label;

    public ReservationTask(@Nonnull ExecutorJenkins owner, @Nonnull Label label) {
        this.jenkins = owner;
        this.label = label;
    }

    @Override public boolean isBuildBlocked() { return false; }
    @Override public String getWhyBlocked() { return null; }

    @Override public String getName() { return jenkins.getName(); }
    @Override public String getFullDisplayName() { return jenkins.getName(); }
    @Override public String getDisplayName() { return jenkins.getName(); }

    @Override public Label getAssignedLabel() {
        return label;
    }
    public ExecutorJenkins getOwner() { return jenkins; }

    @Override public void checkAbortPermission() {throw new AccessDeniedException("Not abortable"); }
    @Override public boolean hasAbortPermission() { return false; }

    public Queue.Item schedule() {
        return Jenkins.getInstance().getQueue().schedule2(this, 0).getItem();
    }

    @Override public String getUrl() {
        // TODO: link to Real Jenkins computer ?
        return "";
    }

    @Override public ResourceList getResourceList() {
        return ResourceList.EMPTY;
    }

    @Override public Node getLastBuiltOn() {
        return null; // Orchestrator do not know that
    }

    @Override public long getEstimatedDuration() {
        return 0; // Orchestrator do not know that
    }

    @Override public @CheckForNull Queue.Executable createExecutable() throws IOException {
        return new ReservationExecutable(this);
    }

    public static class ReservationExecutable implements Queue.Executable {

        private final ReservationTask task;
        private OneShotEvent done = new OneShotEvent();

        public ReservationExecutable(ReservationTask task) {
            this.task = task;
        }

        @Override
        public @Nonnull ReservationTask getParent() {
            return task;
        }

        @Override
        public long getEstimatedDuration() {
            return task.getEstimatedDuration();
        }

        @Override
        public void run() throws AsynchronousExecution {
            Computer owner = Executor.currentExecutor().getOwner();
            if (!(owner instanceof SharedComputer)) throw new IllegalStateException(getClass().getSimpleName() + " running on unexpected computer " + owner);

            SharedComputer computer = (SharedComputer) owner;
            System.out.println("Reserving " + owner.getName() + " for " + task.getName());
            Api.getInstance().utilizeNode(task.jenkins, computer.getNode());
            try {
                done.block();
                System.out.println("Task completed");
            } catch (InterruptedException e) {
                System.out.println("Task interrupted");
                // Interrupted
                e.printStackTrace();
            }
        }

        public void complete(String owner, String state) {
            if (getParent().jenkins.getUrl().toExternalForm().equals(owner)) {
                done.signal();
                return;
            }
            throw new IllegalStateException("Computer not reserved for " + owner);
        }
    }
}

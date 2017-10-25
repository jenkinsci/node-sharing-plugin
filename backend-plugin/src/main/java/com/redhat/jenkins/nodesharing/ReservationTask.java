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

import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.queue.AbstractQueueTask;
import hudson.model.queue.SubTask;
import jenkins.model.queue.AsynchronousExecution;
import org.acegisecurity.AccessDeniedException;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Phony queue task that simulates build execution on executor Jenkins.
 *
 * All requests to reserve a host are modeled as queue items so they are prioritized and assigned to {@link FakeComputer}s
 * naturally.
 *
 * @author ogondza.
 */
public class ReservationTask extends AbstractQueueTask {
    private final @Nonnull String name;
    private final @Nonnull Label label;

    public ReservationTask(@Nonnull String name, @Nonnull Label label) {
        this.name = name;
        this.label = label;
    }

    @Override public boolean isBuildBlocked() { return false; }
    @Override public String getWhyBlocked() { return null; }

    @Override public String getName() { return name; }
    @Override public String getFullDisplayName() { return name; }
    @Override public String getDisplayName() { return name; }

    @Override public Label getAssignedLabel() {
        return label;
    }

    @Override public void checkAbortPermission() {throw new AccessDeniedException("Not abortable"); }
    @Override public boolean hasAbortPermission() { return false; }

    @Override public String getUrl() {
        // TODO: link to Real Jenkins computer ?
        return null;
    }

    @Override public ResourceList getResourceList() {
        return ResourceList.EMPTY;
    }

    @Override public Node getLastBuiltOn() {
        return null; // We do not know that, I guess
    }

    @Override public long getEstimatedDuration() {
        return 0; // We do not know that, I guess
    }

    @CheckForNull @Override public Queue.Executable createExecutable() throws IOException {
        return new LeaseExecutable(this);
    }

    private static final class LeaseExecutable implements Queue.Executable {

        private final ReservationTask task;

        private LeaseExecutable(ReservationTask task) {
            this.task = task;
        }

        @Override
        public @Nonnull SubTask getParent() {
            return task;
        }

        @Override
        public void run() throws AsynchronousExecution {
            System.out.println("Reserving " + Executor.currentExecutor().getOwner().getName() + " for " + task.getName());
            try {
                Thread.sleep(80000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                // Terminate
            }
        }

        @Override
        public long getEstimatedDuration() {
            return task.getEstimatedDuration();
        }
    }
}

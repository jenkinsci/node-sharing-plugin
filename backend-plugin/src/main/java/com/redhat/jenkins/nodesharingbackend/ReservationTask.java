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
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.util.OneShotEvent;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;
import org.acegisecurity.AccessDeniedException;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phony queue task that simulates build execution on executor Jenkins.
 *
 * All requests to reserve a host are modeled as queue items so they are prioritized by Jenkins as well as and assigned
 * to {@link ShareableComputer}s according to labels. Similarly, when computer is occupied by this task it means the host is
 * effectively reserved for executor Jenkins that has created this.
 *
 * @author ogondza.
 */
public class ReservationTask extends AbstractQueueTask implements AccessControlled {
    private static final Logger LOGGER = Logger.getLogger(ReservationTask.class.getName());

    private final @Nonnull ExecutorJenkins jenkins;
    private final @Nonnull Label label;
    private final @Nonnull String taskName;
    private final long qid;
    /**
     * The task is created for reservation we failed to track so the node is already utilized by the executor and therefore
     * the utilizeNode call must not be reattempted. Note backfill are create when Executor is detected to utilize the node
     * already so we have no sane taskName or label for it and it is created once the task is already in progress (unlike
     * regular ReservationTask that is created while Executor build is still in queue)
     */
    private final boolean backfill;

    public ReservationTask(@Nonnull ExecutorJenkins owner, @Nonnull Label label, @Nonnull String taskName, long qid) {
        this.jenkins = owner;
        this.label = label;
        this.taskName = taskName;
        this.qid = qid;
        this.backfill = false;
    }

    public ReservationTask(@Nonnull ExecutorJenkins owner, @Nonnull String host, boolean backfill) {
        this.jenkins = owner;
        this.label = Label.get(host);
        this.taskName = host;
        this.backfill = backfill;
        this.qid = -1;
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
    public @Nonnull String getTaskName() {
        return taskName;
    }

    @Override public void checkAbortPermission() {throw new AccessDeniedException("Not abortable"); }
    @Override public boolean hasAbortPermission() { return false; }
    public @Nonnull ACL getACL() {
        return Jenkins.getInstance().getACL();
    }
    public final void checkPermission(@Nonnull Permission permission) {
        getACL().checkPermission(permission);
    }
    public final boolean hasPermission(@Nonnull Permission permission) {
        return getACL().hasPermission(permission);
    }

    public Queue.Item schedule() {
        return Jenkins.getActiveInstance().getQueue().schedule2(this, 0).getItem();
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

    @Override public @CheckForNull Queue.Executable createExecutable() {
        return new ReservationExecutable(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReservationTask that = (ReservationTask) o;

        if (!Objects.equals(jenkins, that.jenkins)) return false;
        // There is a chance real qid will collide with any magic value we choose for backfill qid. As there is no situation
        // where two backfill tasks should be scheduled for same executor&&host, it is ok to consider them equal.
        if (backfill != that.backfill) return false;
        if (backfill) return Objects.equals(taskName, that.taskName);

        // It is quite unlikely multiple tasks for same qid meets on orchestrator side but it would cause more harm
        // considering them equal as queue would have squashed them. This can, again in even wilder theory, cause the old
        // and unbuildable (label no longer exists) task will prevent incoming task to run and the presence of the new task
        // would keep the zombie in place forever.
        return Objects.equals(qid, that.qid) && Objects.equals(taskName, that.taskName) && Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
        return backfill
                ? Objects.hash(jenkins, taskName)
                : Objects.hash(jenkins, taskName, label, qid)
        ;
    }

    @Override public String toString() {
        return "ReservationTask '" + taskName + "' (qid=" + qid + ") for "
                + jenkins.getName() + " (" + label + ")";
    }

    public static class ReservationExecutable implements Queue.Executable {

        private final @Nonnull ReservationTask task;
        private @CheckForNull String nodeName; // Assigned as soon as execution starts
        private @Nonnull OneShotEvent done = new OneShotEvent();

        protected ReservationExecutable(@Nonnull ReservationTask task) {
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

        public @CheckForNull String getNodeName() {
            return nodeName;
        }

        @Override
        public void run() throws AsynchronousExecution {
            ShareableComputer computer = getExecutingComputer();
            nodeName = computer.getName();
            String executorName = task.getOwner().getName();
            String taskName = "Reservation of " + nodeName + " by " + executorName;
            LOGGER.info(taskName + " started");
            ShareableNode node = computer.getNode();
            if (node == null) throw new AssertionError(); // $COVERAGE-IGNORE$

            if (!task.backfill) {
                while (true) {
                    boolean accepted;
                    try {
                        accepted = Api.getInstance().utilizeNode(task.jenkins, node);
                    } catch (Pool.PoolMisconfigured ex) {
                        // Loop for as long as the pool is broken
                        LOGGER.warning(ex.getMessage());
                        try {
                            Thread.sleep(1000 * 60 * 5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            e.addSuppressed(ex);
                            LOGGER.log(Level.INFO, taskName + " interrupted", e);
                            return;
                        }
                        continue;
                    } catch (Throwable ex) {
                        LOGGER.log(Level.SEVERE, taskName + " failed to get the node utilized", ex);
                        return;
                    }
                    if (!accepted) {
                        LOGGER.info(taskName + " rejected by executor");
                        return; // Abort reservation
                    } else {
                        break; // Reserved successfully - wait for node return
                    }
                }
            }

            try {
                done.block();
                LOGGER.info(taskName + " completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.INFO, taskName + " interrupted", e);
            }
        }

        private @Nonnull ShareableComputer getExecutingComputer() {
            Executor executor = Executor.currentExecutor();
            if (executor == null) throw new IllegalStateException("No running on any executor");
            Computer owner = executor.getOwner();
            if (!(owner instanceof ShareableComputer)) throw new IllegalStateException(getClass().getSimpleName() + " running on unexpected computer " + owner);

            return (ShareableComputer) owner;
        }

        public void complete() {
            done.signal();
        }
    }
}

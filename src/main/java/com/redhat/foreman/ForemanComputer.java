package com.redhat.foreman;

import java.io.IOException;

import java.util.logging.Logger;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.User;
import hudson.model.Queue.Task;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;

/**
 * Foreman Cloud Computer.
 */
public class ForemanComputer extends AbstractCloudComputer<ForemanSharedNode> {

    private static final Logger LOGGER = Logger.getLogger(ForemanComputer.class.getName());

    @Override
    public void taskCompleted(Executor executor, Task task, long durationMS) {
        eagerlyReturnNode(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Task task, long durationMS, Throwable problems) {
        eagerlyReturnNode(executor);
    }

    @Override
    public ForemanSharedNode getNode() {
        return super.getNode();
    }

    @Override
    protected void kill() {
        setTemporarilyOffline(true,
                new OfflineCause.UserCause(User.current(), "Foreman Shared Plugin setTemporarilyOffline()"));
        super.kill();
        try {
            ForemanSharedNode node = this.getNode();
            if (node != null) {
                node.terminate();
            }
        } catch (InterruptedException e) {
            LOGGER.warning("Error during ForemanComputer kill() - " + e.getMessage());
        } catch (IOException e) {
            LOGGER.warning("Error during ForemanComputer kill() - " + e.getMessage());
        }
    }

    /**
     * Utility method to terminate a Foreman shared node.
     * @param c Computer
     * @throws IOException if occurs.
     * @throws InterruptedException if occurs.
     */
    static void terminateForemanComputer(Computer c) throws IOException, InterruptedException {
        if (c instanceof ForemanComputer) {
            ForemanComputer fc = (ForemanComputer)c;
            Node node = fc.getNode();
            if (node != null) {
                ForemanSharedNode sharedNode = (ForemanSharedNode)node;
                sharedNode.terminate();
            }
        }
    }

    /**
     * We want to eagerly return the node to Foreman.
     * @param owner Computer.
     */
    private synchronized void eagerlyReturnNodeLater(final Computer owner) {
        Node node = owner.getNode();
        if (node instanceof ForemanSharedNode) {
            ForemanSharedNode sharedNode = (ForemanSharedNode)node;
            Computer computer = sharedNode.toComputer();
            if (computer != null) {
                computer.setTemporarilyOffline(true,
                        new OfflineCause.UserCause(User.current(), "Foreman Shared Plugin setTemporarilyOffline()"));
            }
            Computer.threadPoolForRemoting.submit(new Runnable() {
                public void run() {
                    try {
                        ForemanComputer.terminateForemanComputer(owner);
                    } catch (InterruptedException e) {
                        LOGGER.warning(e.getMessage());
                    } catch (IOException e) {
                        LOGGER.warning(e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Eagerly return resource to Foreman.
     * @param executor Executor.
     */
    private void eagerlyReturnNode(Executor executor) {
        eagerlyReturnNodeLater(executor.getOwner());
    }

    /**
     * Default constructor.
     * @param slave Foreman slave.
     */
    public ForemanComputer(ForemanSharedNode slave) {
        super(slave);
    }

}

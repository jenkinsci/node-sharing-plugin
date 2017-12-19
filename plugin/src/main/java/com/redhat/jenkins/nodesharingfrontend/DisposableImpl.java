package com.redhat.jenkins.nodesharingfrontend;

import java.util.logging.Logger;

import com.redhat.jenkins.nodesharing.transport.ReturnNodeRequest;
import org.jenkinsci.plugins.resourcedisposer.Disposable;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class DisposableImpl implements Disposable {

    private final String cloudName;
    private final String name;
    private static final Logger LOGGER = Logger.getLogger(DisposableImpl.class.getName());

    public DisposableImpl(String cloudName, String name) {
        this.cloudName = cloudName;
        this.name = name;
    }

    @Nonnull
    @Override
    public State dispose() throws Exception {
        SharedNodeCloud cloud = SharedNodeCloud.getByName(cloudName);
        if (cloud != null) {
            LOGGER.finer("Attempt to release the node: " + name);
            cloud.getApi().returnNode(name, ReturnNodeRequest.Status.OK); // TODO specify status
            LOGGER.finer("[COMPLETED] Attempt to release the node: " + name);
            return State.PURGED;
        }
        String msg = "Node sharing instance '" + cloudName + "' not found.";
        LOGGER.warning(msg);
        return new State.Failed(msg);
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Dispose Shared Node Cloud " + cloudName + " - Shared Node: " + name;
    }

    @CheckForNull
    public String getCloudName() {
        return cloudName;
    }

    @CheckForNull
    public String getName() {
        return name;
    }
}

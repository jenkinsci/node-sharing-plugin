package com.redhat.foreman;

import java.util.logging.Logger;
import org.jenkinsci.plugins.resourcedisposer.Disposable;

import javax.annotation.Nonnull;

/**
 * Created by shebert on 27/09/16.
 */
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
        ForemanSharedNodeCloud cloud = ForemanSharedNodeCloud.getByName(cloudName);
        if (cloud != null) {
            LOGGER.finer("Attempt to release the node: " + name);
            cloud.getForemanAPI().release(name);
            LOGGER.finer("[COMPLETED] Attempt to release the node: " + name);
            return State.PURGED;
        }
        String msg = "Foreman cloud instance '" + cloudName + "' not found.";
        LOGGER.warning(msg);
        return new State.Failed(msg);
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Dispose Foreman Shared Node Cloud " + cloudName + " - Shared Node: " + name;
    }
}

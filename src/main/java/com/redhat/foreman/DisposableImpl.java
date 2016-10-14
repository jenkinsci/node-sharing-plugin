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
            cloud.getForemanAPI().release(name);
            return State.PURGED;
        } else {
            LOGGER.warning("Foreman Shared Node " + name + " is not part of a Foreman Shared Node Cloud");
        }
        return State.TO_DISPOSE;
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Dispose Foreman Shared Node Cloud " + cloudName + " - Shared Node: " + name;
    }
}

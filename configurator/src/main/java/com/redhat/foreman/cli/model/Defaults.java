package com.redhat.foreman.cli.model;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 18/01/17.
 */
public class Defaults {

    @CheckForNull
    public List<Parameter> parameters;

    public Defaults() {
        parameters = new ArrayList<Parameter>();
    }

    @Nonnull
    public List<Parameter> getParameters() {
        if (parameters == null) {
            parameters = new ArrayList<Parameter>();
        }
        return parameters;
    }
}

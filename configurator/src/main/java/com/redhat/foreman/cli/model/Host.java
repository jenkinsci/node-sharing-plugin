package com.redhat.foreman.cli.model;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 05/01/17.
 */
public class Host {
    private String name;

    @Override
    public String toString() {
        return "Host{" +
                "name='" + getName() + '\'' +
                ", parameters=" + parameters +
                ", id=" + id +
                '}';
    }

    @CheckForNull
    public List<Parameter> parameters;

    public int id;

    @CheckForNull
    public Parameter getParameterValue(@Nonnull String name) {
        if (parameters == null) parameters = new ArrayList<Parameter>();
        for (Parameter p: parameters) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public void addParameter(@Nonnull Parameter p) {
        Parameter c = getParameterValue(p.getName());
        if (c == null) {
            parameters.add(p);
        } else {
            parameters.remove(c);
            parameters.add(p);
        }
    }

    @CheckForNull
    public String getName() {
        return name;
    }

    public void setName(@Nonnull String name) {
        this.name = name;
    }
}

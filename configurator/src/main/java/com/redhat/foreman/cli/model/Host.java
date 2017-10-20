package com.redhat.foreman.cli.model;

import hudson.Util;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 05/01/17.
 */
public class Host {
    private String name;

    @Override @Nonnull
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
    public Parameter getParameterValue(@Nonnull final String name) {
        for (Parameter p: getParameters()) {
            String paramName = p.getName();
            if (paramName != null && paramName.equals(name)) {
                return p;
            }
        }
        return null;
    }

    public void addParameter(@Nonnull final Parameter p) {
        String paramName = p.getName();
        if (paramName == null) return;
        Parameter c = getParameterValue(paramName);
        if (c == null) {
            getParameters().add(p);
        } else {
            getParameters().remove(c);
            getParameters().add(p);
        }
    }

    @CheckForNull
    public String getName() {
        return name;
    }

    public void setName(@Nonnull final String name) {
        this.name = name;
    }

    @Nonnull
    public List<Parameter> getParameters() {
        if (parameters == null)
            parameters = new ArrayList<Parameter>();
        return parameters;
    }

    @Nonnull
    public static String getParamOrEmptyString(@Nonnull final Host host, @Nonnull final String sParam) {
        String rVal = "";

        Parameter param = host.getParameterValue(sParam);
        if (param != null) {
            rVal = Util.fixNull(param.getValue());
        }
        return rVal;
    }
}

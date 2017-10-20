package com.redhat.foreman.cli.model;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 16/01/17.
 */
public class Hosts {
    private List<Host> hosts;
    private Defaults defaults;

    @Nonnull
    public List<Host> getHosts() {
        if (hosts == null) hosts = new ArrayList<Host>();
        return hosts;
    }

    @Nonnull
    public Defaults getDefaults() {
        if (defaults == null) defaults = new Defaults();
        return defaults;
    }

    @CheckForNull
    public Parameter getParameterValue(@Nonnull final String name) {
        for (Parameter p: getDefaults().getParameters()) {
            String paramName = p.getName();
            if (paramName != null && paramName.equals(name)) {
                return p;
            }
        }
        return null;
    }

    public void addDefaultParameter(@Nonnull final Parameter p) {
        String paramName = p.getName();
        if (paramName == null) return;
        Parameter c = getParameterValue(paramName);
        if (c == null) {
            getDefaults().getParameters().add(p);
        } else {
            getDefaults().getParameters().remove(c);
            getDefaults().getParameters().add(p);
        }
    }


    @Override @Nonnull
    public String toString() {
        return "Hosts{" +
                "hosts=" + hosts +
                '}';
    }
}

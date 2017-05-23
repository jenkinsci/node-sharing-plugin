package com.redhat.foreman.cli.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 16/01/17.
 */
public class Hosts {
    private List<Host> hosts;
    private Defaults defaults;

    public List<Host> getHosts() {
        if (hosts == null) hosts = new ArrayList<Host>();
        return hosts;
    }

    public Defaults getDefaults() {
        if (defaults == null) defaults = new Defaults();
        return defaults;
    }

    public Parameter getParameterValue(String name) {
        if (getDefaults().parameters == null) getDefaults().parameters = new ArrayList<Parameter>();
        for (Parameter p: getDefaults().parameters) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public void addDefaultParameter(Parameter p) {
        Parameter c = getParameterValue(p.getName());
        if (c == null) {
            getDefaults().parameters.add(p);
        } else {
            getDefaults().parameters.remove(c);
            getDefaults().parameters.add(p);
        }
    }


    @Override
    public String toString() {
        return "Hosts{" +
                "hosts=" + hosts +
                '}';
    }
}

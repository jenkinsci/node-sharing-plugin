package com.scoheb.foreman.cli.model;

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

    public List<Parameter> parameters;
    public int id;

    public Parameter getParameterValue(String name) {
        if (parameters == null) parameters = new ArrayList<Parameter>();
        for (Parameter p: parameters) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public void addParameter(Parameter p) {
        Parameter c = getParameterValue(p.getName());
        if (c == null) {
            parameters.add(p);
        } else {
            parameters.remove(c);
            parameters.add(p);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

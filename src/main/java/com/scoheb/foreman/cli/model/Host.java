package com.scoheb.foreman.cli.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 05/01/17.
 */
public class Host {
    public String name;
    public String ip;
    public String domain_name;

    @Override
    public String toString() {
        return "Host{" +
                "name='" + name + '\'' +
                ", ip='" + ip + '\'' +
                ", domain_name='" + domain_name + '\'' +
                ", parameters=" + parameters +
                ", id=" + id +
                '}';
    }

    public List<Parameter> parameters;
    public int id;

    public Parameter getParameterValue(String name) {
        if (parameters == null) parameters = new ArrayList<Parameter>();
        for (Parameter p: parameters) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }
}

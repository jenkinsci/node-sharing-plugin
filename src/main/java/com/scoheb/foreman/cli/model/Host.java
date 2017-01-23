package com.scoheb.foreman.cli.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by shebert on 05/01/17.
 */
public class Host {
    public String name;
    public transient static Map<String, String> parameterMapping = new HashMap<>();
    static {
        parameterMapping.put("labels", "JENKINS_LABEL");
        parameterMapping.put("remoteFs", "JENKINS_SLAVE_REMOTE_FSROOT");
    }

    @Override
    public Host clone() {
        Host newHost = new Host();
        newHost.name = this.name;
        newHost.parameters = this.parameters;
        newHost.id = 0;
        return newHost;
    }

    @Override
    public String toString() {
        return "Host{" +
                "name='" + name + '\'' +
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

    public void addParameter(Parameter p) {
        Parameter c = getParameterValue(p.name);
        if (c == null) {
            parameters.add(p);
        } else {
            parameters.remove(c);
            parameters.add(p);
        }
    }
}

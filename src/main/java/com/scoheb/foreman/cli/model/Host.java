package com.scoheb.foreman.cli.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by shebert on 05/01/17.
 */
public class Host {
    public String name;
    @SerializedName("ip")
    public String ip_address;
    public String domain_name;

    @Override
    public String toString() {
        return "Host{" +
                "name='" + name + '\'' +
                ", ip_address='" + ip_address + '\'' +
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

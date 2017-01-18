package com.scoheb.foreman.cli.model;

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

    @Override
    public String toString() {
        return "Hosts{" +
                "hosts=" + hosts +
                '}';
    }
}

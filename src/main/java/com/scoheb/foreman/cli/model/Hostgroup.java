package com.scoheb.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class Hostgroup {
    public String name;

    @Override
    public String toString() {
        return "Hostgroup{" +
                "name='" + name + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;
}

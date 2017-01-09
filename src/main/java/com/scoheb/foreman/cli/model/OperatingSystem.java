package com.scoheb.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class OperatingSystem {
    public String name;
    public String major;
    public String minor;
    public String family;

    @Override
    public String toString() {
        return "OperatingSystem{" +
                "name='" + name + '\'' +
                ", major='" + major + '\'' +
                ", minor='" + minor + '\'' +
                ", family='" + family + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;
}

package com.scoheb.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class Medium {
    public String name;
    public String path;
    public String os_family;

    @Override
    public String toString() {
        return "Medium{" +
                "name='" + name + '\'' +
                ", path='" + path + '\'' +
                ", os_family='" + os_family + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;
}

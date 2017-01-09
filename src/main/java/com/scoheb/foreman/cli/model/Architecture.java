package com.scoheb.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class Architecture {
    public String name;

    @Override
    public String toString() {
        return "Architecture{" +
                "name='" + name + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;
}

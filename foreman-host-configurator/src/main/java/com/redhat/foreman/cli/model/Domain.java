package com.redhat.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class Domain {
    private String name;

    @Override
    public String toString() {
        return "Domain{" +
                "name='" + getName() + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

package com.scoheb.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class Parameter {
    public Parameter(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String name;
    public String value;

    @Override
    public String toString() {
        return "Parameter{" +
                "name='" + name + '\'' +
                ", value=" + value +
                ", id=" + id +
                '}';
    }

    public int id;
}

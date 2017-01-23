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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Parameter parameter = (Parameter) o;

        if (!name.equals(parameter.name)) return false;
        return value.equals(parameter.value);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + value.hashCode();
        result = 31 * result + id;
        return result;
    }
}

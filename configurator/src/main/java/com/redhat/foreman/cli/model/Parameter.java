package com.redhat.foreman.cli.model;

/**
 * Created by shebert on 05/01/17.
 */
public class Parameter {
    public Parameter(String name, String value) {
        this.setName(name);
        this.setValue(value);
    }

    private String name;
    private String value;

    @Override
    public String toString() {
        return "Parameter{" +
                "name='" + getName() + '\'' +
                ", value=" + getValue() +
                ", id=" + id +
                '}';
    }

    public int id;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Parameter parameter = (Parameter) o;

        if (!getName().equals(parameter.getName())) return false;
        return getValue().equals(parameter.getValue());
    }

    @Override
    public int hashCode() {
        int result = getName().hashCode();
        result = 31 * result + getValue().hashCode();
        result = 31 * result + id;
        return result;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

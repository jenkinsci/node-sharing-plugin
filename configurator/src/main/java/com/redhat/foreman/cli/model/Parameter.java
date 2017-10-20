package com.redhat.foreman.cli.model;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Created by shebert on 05/01/17.
 */
public class Parameter {
    public Parameter(@Nonnull final String name, @Nonnull final String value) {
        this.setName(name);
        this.setValue(value);
    }

    private String name;
    private String value;

    @Override @Nonnull
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

        if (!(o instanceof Parameter)) return false;
        Parameter parameter = (Parameter) o;

        String paramName = parameter.getName();
        String myName = getName();
        if (paramName != null && myName != null && !myName.equals(paramName)) return false;
        String paramValue = parameter.getValue();
        String myValue = getValue();
        return (paramValue != null && myValue != null && myValue.equals(paramValue));
    }

    @Override
    public int hashCode() {
        String paramName = getName();
        int result = paramName == null ? 1: paramName.hashCode();
        String paramValue = getValue();
        result = 31 * result + (paramValue == null ? 1 : paramValue.hashCode());
        result = 31 * result + id;
        return result;
    }

    @CheckForNull
    public String getValue() { return value; }

    public void setValue(@Nonnull final String value) {
        this.value = value;
    }

    @CheckForNull
    public String getName() {
        return name;
    }

    public void setName(@Nonnull final String name) {
        this.name = name;
    }
}

package com.redhat.foreman.cli.model;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Created by shebert on 05/01/17.
 */
public class OperatingSystem {
    private String name;
    private String major;
    private String minor;
    private String family;

    @Override @Nonnull
    public String toString() {
        return "OperatingSystem{" +
                "name='" + getName() + '\'' +
                ", major='" + getMajor() + '\'' +
                ", minor='" + getMinor() + '\'' +
                ", family='" + getFamily() + '\'' +
                ", id=" + id +
                '}';
    }

    public int id;

    @CheckForNull
    public String getName() {
        return name;
    }

    public void setName(@Nonnull final String name) {
        this.name = name;
    }

    @CheckForNull
    public String getMajor() {
        return major;
    }

    public void setMajor(@Nonnull final String major) {
        this.major = major;
    }

    @CheckForNull
    public String getMinor() {
        return minor;
    }

    public void setMinor(@Nonnull final String minor) {
        this.minor = minor;
    }

    @CheckForNull
    public String getFamily() {
        return family;
    }

    public void setFamily(@Nonnull final String family) {
        this.family = family;
    }
}
